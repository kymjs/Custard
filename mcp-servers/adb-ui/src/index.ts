#!/usr/bin/env node

import { Server } from "@modelcontextprotocol/sdk/server/index.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import {
  ListToolsRequestSchema,
  CallToolRequestSchema,
} from "@modelcontextprotocol/sdk/types.js";
import {
  dumpUiTree,
  tap,
  boundsToCenter,
  findAllNodeTags,
  parseNodeAttrs,
  nodeMatches,
} from "./adb.js";

const server = new Server(
  {
    name: "adb-ui-mcp",
    version: "1.0.0",
  },
  {
    capabilities: {
      tools: {},
    },
  }
);

function escapeXml(s: string): string {
  return s
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

/**
 * Build a simplified text tree from uiautomator XML for display.
 */
function xmlToSimplifiedTree(xml: string): string {
  const lines: string[] = [];
  const nodeRe = /<node[^>]*>/g;
  let m: RegExpExecArray | null;
  while ((m = nodeRe.exec(xml)) !== null) {
    const tag = m[0];
    const attrs = parseNodeAttrs(tag);
    const classShort = attrs.class ? attrs.class.split(".").pop() : "";
    const text = attrs.text ?? attrs["content-desc"] ?? "";
    const rid = attrs["resource-id"] ? ` id=${attrs["resource-id"]}` : "";
    const bounds = attrs.bounds ?? "";
    const line = `${classShort}${rid}${text ? ` text="${escapeXml(text.slice(0, 50))}"` : ""} ${bounds}`.trim();
    lines.push(line);
  }
  return lines.length ? lines.join("\n") : xml.slice(0, 8000);
}

server.setRequestHandler(ListToolsRequestSchema, async () => ({
  tools: [
    {
      name: "get_ui_tree",
      description:
        "Get the current Android screen UI hierarchy (accessibility tree) via ADB. Returns a simplified text representation of the UI tree. Requires ADB and a connected device.",
      inputSchema: {
        type: "object",
        properties: {
          display_id: {
            type: "string",
            description: "Optional display id (e.g. '0' or '1' for secondary display).",
          },
        },
      },
    },
    {
      name: "click_view",
      description:
        "Find a view in the current UI tree by resourceId, text, contentDesc or className, then simulate a tap at its center. Uses ADB uiautomator dump and input tap.",
      inputSchema: {
        type: "object",
        properties: {
          resourceId: { type: "string", description: "Resource id of the view (e.g. 'button_ok')." },
          text: { type: "string", description: "Exact or partial text of the view." },
          contentDesc: { type: "string", description: "Content description of the view." },
          className: { type: "string", description: "Class name of the view (e.g. 'Button')." },
          index: { type: "number", description: "Zero-based index when multiple views match (default 0)." },
          partialMatch: {
            type: "boolean",
            description: "If true, match text/contentDesc as substring (default false).",
          },
          display_id: { type: "string", description: "Optional display id for dump and tap." },
        },
      },
    },
    {
      name: "tap",
      description: "Simulate a tap at the given (x, y) coordinates via ADB input tap.",
      inputSchema: {
        type: "object",
        properties: {
          x: { type: "number", description: "X coordinate in pixels." },
          y: { type: "number", description: "Y coordinate in pixels." },
          display_id: { type: "string", description: "Optional display id." },
        },
        required: ["x", "y"],
      },
    },
  ],
}));

server.setRequestHandler(CallToolRequestSchema, async (request) => {
  const { name, arguments: args } = request.params;
  const arg = (args as Record<string, unknown>) ?? {};
  const getStr = (k: string): string | undefined => {
    const v = arg[k];
    return typeof v === "string" ? v : undefined;
  };
  const getNum = (k: string): number | undefined => {
    const v = arg[k];
    if (typeof v === "number") return v;
    if (typeof v === "string") return parseInt(v, 10);
    return undefined;
  };
  const getBool = (k: string): boolean => {
    const v = arg[k];
    if (typeof v === "boolean") return v;
    if (v === "true") return true;
    return false;
  };

  try {
    if (name === "get_ui_tree") {
      const displayId = getStr("display_id");
      const { xml, error } = dumpUiTree(displayId);
      if (error) {
        return {
          content: [{ type: "text", text: `Error: ${error}` }],
          isError: true,
        };
      }
      const simplified = xmlToSimplifiedTree(xml);
      return {
        content: [{ type: "text", text: simplified || "(empty UI dump)" }],
      };
    }

    if (name === "tap") {
      const x = getNum("x");
      const y = getNum("y");
      const displayId = getStr("display_id");
      if (x == null || y == null) {
        return {
          content: [{ type: "text", text: "Error: x and y are required." }],
          isError: true,
        };
      }
      const { success, error } = tap(x, y, displayId);
      if (!success) {
        return {
          content: [{ type: "text", text: `Tap failed: ${error ?? "unknown"}` }],
          isError: true,
        };
      }
      return {
        content: [{ type: "text", text: `Tapped at (${x}, ${y}).` }],
      };
    }

    if (name === "click_view") {
      const displayId = getStr("display_id");
      const { xml, error: dumpError } = dumpUiTree(displayId);
      if (dumpError) {
        return {
          content: [{ type: "text", text: `UI dump failed: ${dumpError}` }],
          isError: true,
        };
      }
      const resourceId = getStr("resourceId");
      const text = getStr("text");
      const contentDesc = getStr("contentDesc");
      const className = getStr("className");
      const index = getNum("index") ?? 0;
      const partialMatch = getBool("partialMatch");

      const nodes = findAllNodeTags(xml);
      const matching: { tag: string; attrs: ReturnType<typeof parseNodeAttrs> }[] = [];
      for (const m of nodes) {
        const attrs = parseNodeAttrs(m[0]);
        if (!attrs.bounds) continue;
        if (
          nodeMatches(attrs, {
            resourceId,
            className,
            contentDesc,
            text,
            partialMatch,
          })
        ) {
          matching.push({ tag: m[0], attrs });
        }
      }

      if (matching.length === 0) {
        return {
          content: [
            {
              type: "text",
              text: "No matching element found. Use get_ui_tree to inspect the current screen.",
            },
          ],
          isError: true,
        };
      }
      if (index < 0 || index >= matching.length) {
        return {
          content: [
            {
              type: "text",
              text: `Index ${index} out of range. Found ${matching.length} matching elements.`,
            },
          ],
          isError: true,
        };
      }
      const chosen = matching[index];
      const center = boundsToCenter(chosen.attrs.bounds!);
      if (!center) {
        return {
          content: [{ type: "text", text: "Failed to parse bounds of the element." }],
          isError: true,
        };
      }
      const { success, error } = tap(center.x, center.y, displayId);
      if (!success) {
        return {
          content: [{ type: "text", text: `Tap failed: ${error ?? "unknown"}` }],
          isError: true,
        };
      }
      return {
        content: [
          {
            type: "text",
            text: `Clicked element at (${center.x}, ${center.y}) (index ${index} of ${matching.length} matches).`,
          },
        ],
      };
    }

    return {
      content: [{ type: "text", text: `Unknown tool: ${name}` }],
      isError: true,
    };
  } catch (e) {
    const message = e instanceof Error ? e.message : String(e);
    return {
      content: [{ type: "text", text: `Error: ${message}` }],
      isError: true,
    };
  }
});

async function main() {
  const transport = new StdioServerTransport();
  await server.connect(transport);
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
