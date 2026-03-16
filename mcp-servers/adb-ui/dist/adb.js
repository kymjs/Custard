"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.adbShell = adbShell;
exports.dumpUiTree = dumpUiTree;
exports.boundsToCenter = boundsToCenter;
exports.parseNodeAttrs = parseNodeAttrs;
exports.findAllNodeTags = findAllNodeTags;
exports.nodeMatches = nodeMatches;
exports.tap = tap;
const child_process_1 = require("child_process");
const DUMP_PATH = "/sdcard/window_dump.xml";
/**
 * Run adb shell command. Assumes adb is in PATH and device is connected.
 */
function adbShell(cmd) {
    try {
        const full = `adb shell "${cmd.replace(/"/g, '\\"')}"`;
        const stdout = (0, child_process_1.execSync)(full, { encoding: "utf8", maxBuffer: 4 * 1024 * 1024 });
        return { stdout: stdout.trim(), stderr: "", success: true };
    }
    catch (e) {
        const err = e;
        const stderr = err.stderr ? String(err.stderr) : err.message ?? "";
        return { stdout: "", stderr, success: false };
    }
}
/**
 * Dump UI hierarchy to /sdcard/window_dump.xml and return XML string.
 * @param displayId - Optional display id (e.g. "0", "1" for secondary screen).
 */
function dumpUiTree(displayId) {
    const dumpCmd = displayId != null && displayId !== ""
        ? `uiautomator dump --display-id ${displayId} ${DUMP_PATH}`
        : `uiautomator dump ${DUMP_PATH}`;
    const dumpResult = adbShell(dumpCmd);
    if (!dumpResult.success) {
        return { xml: "", error: `uiautomator dump failed: ${dumpResult.stderr}` };
    }
    const readResult = adbShell(`cat ${DUMP_PATH}`);
    if (!readResult.success) {
        return { xml: "", error: `Failed to read dump file: ${readResult.stderr}` };
    }
    return { xml: readResult.stdout };
}
/**
 * Parse bounds string "[left,top][right,bottom]" to center { x, y }.
 */
function boundsToCenter(bounds) {
    const m = /\[(\d+),(\d+)\]\[(\d+),(\d+)\]/.exec(bounds);
    if (!m)
        return null;
    const x1 = parseInt(m[1], 10);
    const y1 = parseInt(m[2], 10);
    const x2 = parseInt(m[3], 10);
    const y2 = parseInt(m[4], 10);
    return { x: Math.floor((x1 + x2) / 2), y: Math.floor((y1 + y2) / 2) };
}
/**
 * Simple regex-based extraction of <node ... /> attributes from uiautomator XML.
 * Does not handle nested XML; we only need top-level node attributes for matching.
 */
function parseNodeAttrs(nodeTag) {
    const get = (name) => {
        const re = new RegExp(`${name}="([^"]*)"`);
        const m = re.exec(nodeTag);
        return m ? m[1] : undefined;
    };
    return {
        class: get("class"),
        text: get("text"),
        "content-desc": get("content-desc"),
        "resource-id": get("resource-id"),
        bounds: get("bounds"),
        clickable: get("clickable"),
    };
}
/**
 * Find all <node ...> opening tags in XML (flat, no nesting structure).
 */
function findAllNodeTags(xml) {
    const nodeRe = /<node[^>]*>/g;
    const list = [];
    let m;
    while ((m = nodeRe.exec(xml)) !== null) {
        list.push(m);
    }
    return list;
}
/**
 * Match a node tag string against optional resourceId, className, contentDesc.
 * partialMatch: if true, attribute value may be a substring.
 */
function nodeMatches(attrs, options) {
    const { resourceId, className, contentDesc, text, partialMatch = false } = options;
    const match = (actual, wanted, isResourceId) => {
        if (wanted == null || wanted === "")
            return true;
        if (actual == null || actual === "")
            return false;
        if (partialMatch)
            return actual.includes(wanted);
        if (isResourceId)
            return (actual === wanted ||
                actual.endsWith("/" + wanted) ||
                actual.endsWith(":id/" + wanted));
        return actual === wanted;
    };
    if (resourceId != null &&
        resourceId !== "" &&
        !match(attrs["resource-id"], resourceId, true))
        return false;
    if (className != null && className !== "") {
        const cls = attrs.class;
        const want = className.includes(".") ? className : className;
        if (!cls)
            return false;
        if (partialMatch ? !cls.includes(want) : !cls.endsWith(want))
            return false;
    }
    if (contentDesc != null &&
        contentDesc !== "" &&
        !match(attrs["content-desc"], contentDesc, false))
        return false;
    if (text != null && text !== "" && !match(attrs.text, text, false))
        return false;
    return true;
}
/**
 * Execute tap at (x, y).
 */
function tap(x, y, displayId) {
    const prefix = displayId != null && displayId !== "" ? `-d ${displayId} ` : "";
    const result = adbShell(`input ${prefix}tap ${x} ${y}`);
    return { success: result.success, error: result.success ? undefined : result.stderr };
}
