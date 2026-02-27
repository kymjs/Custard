import type { ComposeDslContext, ComposeNode } from "../../../types/compose-dsl";
import { resolveWindowsSetupI18n, type WindowsSetupI18n } from "../../i18n";

const WINDOWS_PACKAGE_NAME = "windows_control";
const WINDOWS_TOOL_TEST_CONNECTION = "windows_test_connection";
const PC_AGENT_RESOURCE_KEY = "pc_agent_zip";

const KEY_BASE_URL = "WINDOWS_AGENT_BASE_URL";
const KEY_TOKEN = "WINDOWS_AGENT_TOKEN";
const KEY_DEFAULT_SHELL = "WINDOWS_AGENT_DEFAULT_SHELL";
const KEY_TIMEOUT_MS = "WINDOWS_AGENT_TIMEOUT_MS";

type ConnectionCardStatus = "idle" | "checking" | "notConfigured" | "success" | "failed";

interface ConnectionCardModel {
  status: ConnectionCardStatus;
  baseUrl: string;
  packageVersion: string;
  agentVersion: string;
  durationMs: string;
  command: string;
  error: string;
}

interface ParsedConnectionPayload {
  success: boolean | null;
  baseUrl: string;
  packageVersion: string;
  agentVersion: string;
  durationMs: string;
  command: string;
  error: string;
}

function resolveText(ctx: ComposeDslContext): WindowsSetupI18n {
  const rawLocale = ctx.getLocale ? ctx.getLocale() : "";
  const locale = String(rawLocale || "").trim().toLowerCase();
  const preferredLocale = locale.startsWith("en") ? "en-US" : "zh-CN";
  return resolveWindowsSetupI18n(preferredLocale);
}

function useStateValue<T>(ctx: ComposeDslContext, key: string, initialValue: T): {
  value: T;
  set: (value: T) => void;
} {
  const pair = ctx.useState<T>(key, initialValue);
  return { value: pair[0], set: pair[1] };
}

function firstNonBlank(...values: Array<string | null | undefined>): string {
  for (const value of values) {
    if (typeof value === "string" && value.trim()) {
      return value.trim();
    }
  }
  return "";
}

function toErrorText(error: unknown): string {
  if (error instanceof Error) {
    return error.message || "unknown";
  }
  return String(error || "unknown");
}

function toTimeoutMs(raw: string): number {
  const value = Number(raw);
  if (!Number.isFinite(value) || value <= 0) {
    return 5000;
  }
  return Math.floor(value);
}

function extractPortFromUrl(raw: string): number | null {
  const value = raw.trim();
  if (!value) {
    return null;
  }
  try {
    const withScheme =
      value.startsWith("http://") || value.startsWith("https://") ? value : `http://${value}`;
    const url = new URL(withScheme);
    const port = Number(url.port || 0);
    return port >= 1 && port <= 65535 ? port : null;
  } catch {
    return null;
  }
}

function normalizeBaseUrlInput(input: string, fallbackBaseUrl: string): string {
  const raw = input.trim().replace(/\/+$/g, "");
  if (!raw) {
    return "";
  }

  const fallbackPort = extractPortFromUrl(fallbackBaseUrl) || 58321;
  const withScheme =
    raw.startsWith("http://") || raw.startsWith("https://") ? raw : `http://${raw}`;

  try {
    const url = new URL(withScheme);
    const scheme = (url.protocol || "http:").replace(":", "").toLowerCase() === "https" ? "https" : "http";
    const host = url.hostname.trim();
    if (!host) {
      return withScheme;
    }
    const portRaw = Number(url.port || 0);
    const port = portRaw >= 1 && portRaw <= 65535 ? portRaw : fallbackPort;
    const path = url.pathname && url.pathname !== "/" ? url.pathname : "";
    return `${scheme}://${host}:${port}${path}${url.search}${url.hash}`.replace(/\/+$/g, "");
  } catch {
    return withScheme;
  }
}

function parseConnectionPayload(raw: string): ParsedConnectionPayload | null {
  if (!raw.trim()) {
    return null;
  }
  try {
    const obj = JSON.parse(raw) as Record<string, unknown>;
    const health =
      obj.health && typeof obj.health === "object" ? (obj.health as Record<string, unknown>) : null;
    const durationRaw = obj.durationMs;
    const durationMs =
      durationRaw == null ? "" : typeof durationRaw === "string" ? durationRaw.trim() : String(durationRaw);
    const hasSuccess = typeof obj.success === "boolean";
    return {
      success: hasSuccess ? (obj.success as boolean) : null,
      baseUrl: typeof obj.agentBaseUrl === "string" ? obj.agentBaseUrl.trim() : "",
      packageVersion: typeof obj.packageVersion === "string" ? obj.packageVersion.trim() : "",
      agentVersion: firstNonBlank(
        typeof obj.agentVersion === "string" ? obj.agentVersion : "",
        health && typeof health.version === "string" ? health.version : "",
        health && typeof health.agentVersion === "string" ? health.agentVersion : ""
      ),
      durationMs,
      command: typeof obj.command === "string" ? obj.command.trim() : "",
      error: typeof obj.error === "string" ? obj.error.trim() : ""
    };
  } catch {
    return null;
  }
}

async function resolveToolName(
  ctx: ComposeDslContext,
  packageName: string,
  toolName: string
): Promise<string> {
  if (ctx.resolveToolName) {
    const resolved = await ctx.resolveToolName({ packageName, toolName, preferImported: true });
    const value = String(resolved || "").trim();
    if (value) {
      return value;
    }
  }
  return `${packageName}:${toolName}`;
}

function resolveRuntimePackageName(ctx: ComposeDslContext, fallback: string): string {
  const currentPackageName = String(ctx.getCurrentPackageName?.() || "").trim();
  const currentToolPkgId = String(ctx.getCurrentToolPkgId?.() || "").trim();
  if (!currentPackageName) {
    return fallback;
  }
  if (currentToolPkgId && currentPackageName === currentToolPkgId) {
    return fallback;
  }
  return currentPackageName;
}

async function ensureImportedAndUsed(ctx: ComposeDslContext, packageName: string): Promise<void> {
  const imported = ctx.isPackageImported ? !!(await ctx.isPackageImported(packageName)) : false;
  if (!imported && ctx.importPackage) {
    const result = await ctx.importPackage(packageName);
    const message = String(result || "").toLowerCase();
    if (message.includes("error") || message.includes("failed") || message.includes("not found")) {
      throw new Error(String(result || "import package failed"));
    }
  }
  if (ctx.usePackage) {
    const useResult = await ctx.usePackage(packageName);
    const useText = String(useResult || "");
    if (useText && useText.toLowerCase().includes("error")) {
      throw new Error(useText);
    }
  }
}

function buildConnectionMetaRows(
  ctx: ComposeDslContext,
  text: WindowsSetupI18n,
  model: ConnectionCardModel,
  contentColor: string
): ComposeNode[] {
  const rows: ComposeNode[] = [];
  const addRow = (label: string, value: string): void => {
    const text = value.trim();
    if (!text) {
      return;
    }
    rows.push(
      ctx.Row({ fillMaxWidth: true, verticalAlignment: "start" }, [
        ctx.Text({
          text: `${label}:`,
          style: "bodySmall",
          color: contentColor,
          fontWeight: "semiBold",
          width: 96
        }),
        ctx.Text({
          text,
          style: "bodySmall",
          color: contentColor,
          weight: 1
        })
      ])
    );
  };

  addRow(text.connectionFieldBaseUrl, model.baseUrl);
  addRow(text.connectionFieldPackageVersion, model.packageVersion);
  addRow(text.connectionFieldAgentVersion, model.agentVersion);
  addRow(text.connectionFieldDuration, model.durationMs);
  addRow(text.connectionFieldCommand, model.command);
  addRow(text.connectionFieldError, model.error);
  return rows;
}

export default function Screen(ctx: ComposeDslContext): ComposeNode {
  const TEXT = resolveText(ctx);
  const baseUrlState = useStateValue(ctx, "baseUrl", ctx.getEnv(KEY_BASE_URL) || "");
  const tokenState = useStateValue(ctx, "token", ctx.getEnv(KEY_TOKEN) || "");
  const defaultShellState = useStateValue(ctx, "defaultShell", ctx.getEnv(KEY_DEFAULT_SHELL) || "");
  const timeoutMsState = useStateValue(ctx, "timeoutMs", ctx.getEnv(KEY_TIMEOUT_MS) || "");
  const pastedConfigState = useStateValue(ctx, "pastedConfigText", "");

  const isSharingZipState = useStateValue(ctx, "isSharingZip", false);
  const isSavingConfigState = useStateValue(ctx, "isSavingConfig", false);
  const isCheckingConnectionState = useStateValue(ctx, "isCheckingConnection", false);

  const step1MessageState = useStateValue(ctx, "step1Message", "");
  const step2MessageState = useStateValue(ctx, "step2Message", "");
  const errorMessageState = useStateValue(ctx, "errorMessage", "");

  const connectionFixBaseUrlState = useStateValue(ctx, "connectionFixBaseUrlInput", baseUrlState.value);
  const connectionCardState = useStateValue<ConnectionCardModel>(ctx, "connectionCard", {
    status: "idle",
    baseUrl: "",
    packageVersion: "",
    agentVersion: "",
    durationMs: "",
    command: "",
    error: ""
  });

  const hasInitializedState = useStateValue(ctx, "hasInitialized", false);

  const setErrorMessage = (message: string): void => {
    errorMessageState.set(message);
  };

  const setConnectionCard = (next: ConnectionCardModel): void => {
    connectionCardState.set(next);
  };

  const checkConnectionByTool = async (): Promise<void> => {
    isCheckingConnectionState.set(true);
    setConnectionCard({
      ...connectionCardState.value,
      status: "checking"
    });
    setErrorMessage("");
    try {
      const packageName = resolveRuntimePackageName(ctx, WINDOWS_PACKAGE_NAME);
      const savedBaseUrl = (ctx.getEnv(KEY_BASE_URL) || "").trim();
      const savedToken = (ctx.getEnv(KEY_TOKEN) || "").trim();
      const imported = ctx.isPackageImported ? !!(await ctx.isPackageImported(packageName)) : true;

      if (!imported || !savedBaseUrl || !savedToken) {
        setConnectionCard({
          status: "notConfigured",
          baseUrl: savedBaseUrl,
          packageVersion: "",
          agentVersion: "",
          durationMs: "",
          command: "",
          error: TEXT.packageNotEnabled
        });
        connectionFixBaseUrlState.set(savedBaseUrl);
        return;
      }

      const resolved = await resolveToolName(ctx, packageName, WINDOWS_TOOL_TEST_CONNECTION);
      const candidates = [
        resolved,
        `${packageName}:${WINDOWS_TOOL_TEST_CONNECTION}`
      ].filter((item, index, arr) => item && arr.indexOf(item) === index);

      let rawResult: unknown = null;
      let lastError = "unknown";
      for (const toolName of candidates) {
        try {
          const connectionTimeoutMs = Math.min(
            toTimeoutMs(ctx.getEnv(KEY_TIMEOUT_MS) || "5000"),
            5000
          );
          rawResult = await ctx.callTool(toolName, { timeout_ms: connectionTimeoutMs });
          lastError = "";
          break;
        } catch (error) {
          lastError = toErrorText(error);
        }
      }

      if (lastError && rawResult == null) {
        setConnectionCard({
          status: "failed",
          baseUrl: savedBaseUrl,
          packageVersion: "",
          agentVersion: "",
          durationMs: "",
          command: "",
          error: lastError
        });
        connectionFixBaseUrlState.set(savedBaseUrl);
        return;
      }

      const rawPayload =
        typeof rawResult === "string" ? rawResult : JSON.stringify(rawResult == null ? {} : rawResult);
      const parsed = parseConnectionPayload(rawPayload);
      const resultObj =
        rawResult && typeof rawResult === "object" ? (rawResult as Record<string, unknown>) : null;

      const successByResultObj = resultObj && typeof resultObj.success === "boolean" ? resultObj.success : null;
      const actualSuccess = parsed?.success ?? successByResultObj ?? true;
      const errorText = firstNonBlank(
        parsed?.error,
        resultObj && typeof resultObj.error === "string" ? resultObj.error : "",
        actualSuccess ? "" : rawPayload
      );

      const nextCard: ConnectionCardModel = {
        status: actualSuccess ? "success" : "failed",
        baseUrl: firstNonBlank(parsed?.baseUrl, savedBaseUrl),
        packageVersion: parsed?.packageVersion || "",
        agentVersion: parsed?.agentVersion || "",
        durationMs: parsed?.durationMs || "",
        command: parsed?.command || "",
        error: errorText
      };
      setConnectionCard(nextCard);
      connectionFixBaseUrlState.set(firstNonBlank(nextCard.baseUrl, savedBaseUrl));
    } catch (error) {
      setConnectionCard({
        ...connectionCardState.value,
        status: "failed",
        error: toErrorText(error)
      });
      connectionFixBaseUrlState.set(firstNonBlank(connectionCardState.value.baseUrl, baseUrlState.value));
    } finally {
      isCheckingConnectionState.set(false);
    }
  };

  const applyNewBaseUrlAndRetry = async (): Promise<void> => {
    setErrorMessage("");
    const rawInput = connectionFixBaseUrlState.value.trim();
    if (!rawInput) {
      setErrorMessage(TEXT.errorHostRequired);
      return;
    }
    const normalized = normalizeBaseUrlInput(rawInput, connectionCardState.value.baseUrl);
    baseUrlState.set(normalized);
    connectionFixBaseUrlState.set(normalized);
    await ctx.setEnv(KEY_BASE_URL, normalized);
    await checkConnectionByTool();
  };

  const sharePcAgentZip = async (): Promise<void> => {
    isSharingZipState.set(true);
    step1MessageState.set("");
    setErrorMessage("");
    try {
      const resource = await ctx.readResource(PC_AGENT_RESOURCE_KEY);
      const path = typeof resource === "string" ? resource.trim() : "";
      if (!path) {
        throw new Error(TEXT.step1MissingResource);
      }
      await ctx.callTool("share_file", {
        path,
        title: TEXT.shareTitle
      });
      step1MessageState.set(`${TEXT.step1SuccessPrefix}${path}`);
    } catch (error) {
      setErrorMessage(`${TEXT.statusErrorPrefix}${toErrorText(error) || TEXT.step1ShareFailed}`);
    } finally {
      isSharingZipState.set(false);
    }
  };

  const saveConfigAndActivatePackage = async (
    baseUrlInput: string,
    tokenInput: string,
    defaultShellInput: string,
    timeoutInput: string
  ): Promise<void> => {
    isSavingConfigState.set(true);
    step2MessageState.set("");
    setErrorMessage("");

    const baseUrlValue = baseUrlInput.trim();
    const tokenValue = tokenInput.trim();
    const shellValue = defaultShellInput.trim();
    const timeoutValue = timeoutInput.trim();

    if (!baseUrlValue) {
      setErrorMessage(TEXT.errorHostRequired);
      isSavingConfigState.set(false);
      return;
    }
    if (!tokenValue) {
      setErrorMessage(TEXT.errorTokenRequired);
      isSavingConfigState.set(false);
      return;
    }

    try {
      await ctx.setEnv(KEY_BASE_URL, baseUrlValue);
      await ctx.setEnv(KEY_TOKEN, tokenValue);
      await ctx.setEnv(KEY_DEFAULT_SHELL, shellValue);
      await ctx.setEnv(KEY_TIMEOUT_MS, timeoutValue);

      const packageName = resolveRuntimePackageName(ctx, WINDOWS_PACKAGE_NAME);
      await ensureImportedAndUsed(ctx, packageName);

      step2MessageState.set(TEXT.successApply);
      await checkConnectionByTool();
    } catch (error) {
      setErrorMessage(`${TEXT.statusErrorPrefix}${toErrorText(error)}`);
    } finally {
      isSavingConfigState.set(false);
    }
  };

  const pasteConfigAndApply = async (): Promise<void> => {
    const raw = pastedConfigState.value.trim();
    if (!raw) {
      setErrorMessage(TEXT.errorPasteEmpty);
      return;
    }

    try {
      const payload = JSON.parse(raw) as Record<string, unknown>;
      const baseUrlValue = String(payload[KEY_BASE_URL] || baseUrlState.value).trim();
      const tokenValue = String(payload[KEY_TOKEN] || tokenState.value).trim();
      const defaultShellValue = String(payload[KEY_DEFAULT_SHELL] || defaultShellState.value).trim();
      const timeoutValue = String(payload[KEY_TIMEOUT_MS] || timeoutMsState.value).trim();

      baseUrlState.set(baseUrlValue);
      tokenState.set(tokenValue);
      defaultShellState.set(defaultShellValue);
      timeoutMsState.set(timeoutValue);
      connectionFixBaseUrlState.set(baseUrlValue);

      await saveConfigAndActivatePackage(
        baseUrlValue,
        tokenValue,
        defaultShellValue,
        timeoutValue
      );
    } catch (error) {
      setErrorMessage(`${TEXT.errorPasteInvalidPrefix}${toErrorText(error)}`);
    }
  };

  const statusConfigByStatus: Record<
    ConnectionCardStatus,
    { text: string; containerColor: string; contentColor: string; icon: string }
  > = {
    idle: {
      text: TEXT.connectionStateIdle,
      containerColor: "surfaceVariant",
      contentColor: "onSurfaceVariant",
      icon: "computer"
    },
    checking: {
      text: TEXT.connectionStateChecking,
      containerColor: "tertiaryContainer",
      contentColor: "onTertiaryContainer",
      icon: "settings"
    },
    notConfigured: {
      text: TEXT.connectionStateNotConfigured,
      containerColor: "secondaryContainer",
      contentColor: "onSecondaryContainer",
      icon: "settings"
    },
    success: {
      text: TEXT.connectionStateSuccess,
      containerColor: "primaryContainer",
      contentColor: "onPrimaryContainer",
      icon: "checkCircle"
    },
    failed: {
      text: TEXT.connectionStateFailed,
      containerColor: "errorContainer",
      contentColor: "onErrorContainer",
      icon: "error"
    }
  };

  const currentStatusUi = statusConfigByStatus[connectionCardState.value.status];

  const connectionCardChildren: ComposeNode[] = [
    ctx.Row({ verticalAlignment: "center" }, [
      ctx.Icon({
        name: currentStatusUi.icon,
        tint: currentStatusUi.contentColor
      }),
      ctx.Spacer({ width: 8 }),
      ctx.Column({ spacing: 2 }, [
        ctx.Text({
          text: TEXT.connectionCardTitle,
          style: "titleMedium",
          color: currentStatusUi.contentColor,
          fontWeight: "semiBold"
        }),
        ctx.Text({
          text: currentStatusUi.text,
          style: "bodySmall",
          color: currentStatusUi.contentColor
        })
      ])
    ])
  ];

  if (connectionCardState.value.status === "checking") {
    connectionCardChildren.push(
      ctx.Row({ verticalAlignment: "center" }, [
        ctx.CircularProgressIndicator({ width: 16, height: 16, strokeWidth: 2, color: currentStatusUi.contentColor }),
        ctx.Spacer({ width: 8 }),
        ctx.Text({
          text: TEXT.checking,
          style: "bodyMedium",
          color: currentStatusUi.contentColor
        })
      ])
    );
  } else {
    connectionCardChildren.push(
      ...buildConnectionMetaRows(ctx, TEXT, connectionCardState.value, currentStatusUi.contentColor)
    );

    if (
      connectionCardState.value.status === "failed" ||
      connectionCardState.value.status === "notConfigured"
    ) {
      connectionCardChildren.push(
        ctx.TextField({
          label: TEXT.connectionFixBaseUrlLabel,
          placeholder: TEXT.connectionFixBaseUrlPlaceholder,
          value: connectionFixBaseUrlState.value,
          onValueChange: connectionFixBaseUrlState.set,
          singleLine: true
        })
      );
      connectionCardChildren.push(
        isCheckingConnectionState.value
          ? ctx.Button({
              enabled: false,
              fillMaxWidth: true,
              onClick: applyNewBaseUrlAndRetry
            }, [
              ctx.Row({ verticalAlignment: "center", horizontalArrangement: "center" }, [
                ctx.CircularProgressIndicator({ width: 16, height: 16, strokeWidth: 2, color: "onPrimary" }),
                ctx.Spacer({ width: 8 }),
                ctx.Text({ text: TEXT.checking })
              ])
            ])
          : ctx.Button({
              text: TEXT.connectionFixApplyButton,
              enabled: connectionFixBaseUrlState.value.trim().length > 0,
              fillMaxWidth: true,
              onClick: applyNewBaseUrlAndRetry
            })
      );
    }
  }

  const rootChildren: ComposeNode[] = [
    ctx.Row({ verticalAlignment: "center" }, [
      ctx.Icon({
        name: "computer",
        tint: "primary"
      }),
      ctx.Spacer({ width: 8 }),
      ctx.Text({
        text: TEXT.title,
        style: "headlineSmall",
        fontWeight: "bold"
      })
    ]),
    ctx.Text({
      text: TEXT.subtitle,
      style: "bodyMedium",
      color: "onSurfaceVariant"
    }),
    ctx.Card({
      fillMaxWidth: true,
      containerColor: currentStatusUi.containerColor,
      contentColor: currentStatusUi.contentColor
    }, [
      ctx.Column({ padding: 16, spacing: 10 }, connectionCardChildren)
    ]),
    ctx.Card({ fillMaxWidth: true }, [
      ctx.Column({ padding: 16, spacing: 10 }, [
        ctx.Row({ verticalAlignment: "center" }, [
          ctx.Icon({ name: "share", tint: "primary" }),
          ctx.Spacer({ width: 8 }),
          ctx.Text({
            text: TEXT.step1Title,
            style: "titleMedium",
            fontWeight: "semiBold"
          })
        ]),
        ctx.Text({
          text: TEXT.step1Desc,
          style: "bodyMedium",
          color: "onSurfaceVariant"
        }),
        isSharingZipState.value
          ? ctx.Button({
              enabled: false,
              fillMaxWidth: true,
              onClick: sharePcAgentZip
            }, [
              ctx.Row({ verticalAlignment: "center", horizontalArrangement: "center" }, [
                ctx.CircularProgressIndicator({ width: 16, height: 16, strokeWidth: 2, color: "onPrimary" }),
                ctx.Spacer({ width: 8 }),
                ctx.Text({ text: TEXT.exporting })
              ])
            ])
          : ctx.Button({
              text: TEXT.step1Button,
              enabled: true,
              fillMaxWidth: true,
              onClick: sharePcAgentZip
            })
      ])
    ]),
    ctx.Card({ fillMaxWidth: true }, [
      ctx.Column({ padding: 16, spacing: 10 }, [
        ctx.Row({ verticalAlignment: "center" }, [
          ctx.Icon({ name: "computer", tint: "primary" }),
          ctx.Spacer({ width: 8 }),
          ctx.Text({
            text: TEXT.step2Title,
            style: "titleMedium",
            fontWeight: "semiBold"
          })
        ]),
        ctx.Text({
          text: TEXT.step2Desc,
          style: "bodyMedium",
          color: "onSurfaceVariant"
        })
      ])
    ]),
    ctx.Card({ fillMaxWidth: true }, [
      ctx.Column({ padding: 16, spacing: 10 }, [
        ctx.Row({ verticalAlignment: "center" }, [
          ctx.Icon({ name: "settings", tint: "primary" }),
          ctx.Spacer({ width: 8 }),
          ctx.Text({
            text: TEXT.step3Title,
            style: "titleMedium",
            fontWeight: "semiBold"
          })
        ]),
        ctx.Text({
          text: TEXT.step3Desc,
          style: "bodyMedium",
          color: "onSurfaceVariant"
        }),
        ctx.TextField({
          label: TEXT.configLabel,
          placeholder: TEXT.configPlaceholder,
          value: pastedConfigState.value,
          onValueChange: pastedConfigState.set,
          minLines: 8
        }),
        ctx.Text({
          text: TEXT.envTip,
          style: "bodySmall",
          color: "onSurfaceVariant"
        }),
        isSavingConfigState.value
          ? ctx.Button({
              enabled: false,
              fillMaxWidth: true,
              onClick: pasteConfigAndApply
            }, [
              ctx.Row({ verticalAlignment: "center", horizontalArrangement: "center" }, [
                ctx.CircularProgressIndicator({ width: 16, height: 16, strokeWidth: 2, color: "onPrimary" }),
                ctx.Spacer({ width: 8 }),
                ctx.Text({ text: TEXT.applying })
              ])
            ])
          : ctx.Button({
              text: TEXT.applyButton,
              enabled: true,
              fillMaxWidth: true,
              onClick: pasteConfigAndApply
            }),
        isCheckingConnectionState.value
          ? ctx.Button({
              enabled: false,
              fillMaxWidth: true,
              onClick: checkConnectionByTool
            }, [
              ctx.Row({ verticalAlignment: "center", horizontalArrangement: "center" }, [
                ctx.CircularProgressIndicator({ width: 16, height: 16, strokeWidth: 2, color: "onPrimary" }),
                ctx.Spacer({ width: 8 }),
                ctx.Text({ text: TEXT.checking })
              ])
            ])
          : ctx.Button({
              text: TEXT.recheckButton,
              enabled: true,
              fillMaxWidth: true,
              onClick: checkConnectionByTool
            })
      ])
    ])
  ];

  if (step1MessageState.value.trim()) {
    rootChildren.push(
      ctx.Card({ containerColor: "primaryContainer", fillMaxWidth: true }, [
        ctx.Row({ padding: 14, verticalAlignment: "center" }, [
          ctx.Icon({ name: "checkCircle", tint: "onPrimaryContainer" }),
          ctx.Spacer({ width: 8 }),
          ctx.Text({
            text: step1MessageState.value,
            style: "bodyMedium",
            color: "onPrimaryContainer"
          })
        ])
      ])
    );
  }

  if (step2MessageState.value.trim()) {
    rootChildren.push(
      ctx.Card({ containerColor: "primaryContainer", fillMaxWidth: true }, [
        ctx.Row({ padding: 14, verticalAlignment: "center" }, [
          ctx.Icon({ name: "checkCircle", tint: "onPrimaryContainer" }),
          ctx.Spacer({ width: 8 }),
          ctx.Text({
            text: step2MessageState.value,
            style: "bodyMedium",
            color: "onPrimaryContainer"
          })
        ])
      ])
    );
  }

  if (errorMessageState.value.trim()) {
    rootChildren.push(
      ctx.Card({ containerColor: "errorContainer", fillMaxWidth: true }, [
        ctx.Row({ padding: 14, verticalAlignment: "center" }, [
          ctx.Icon({ name: "error", tint: "onErrorContainer" }),
          ctx.Spacer({ width: 8 }),
          ctx.Text({
            text: errorMessageState.value,
            style: "bodyMedium",
            color: "onErrorContainer"
          })
        ])
      ])
    );
  }

  return ctx.Column(
    {
      onLoad: async () => {
        if (!hasInitializedState.value) {
          hasInitializedState.set(true);
          await checkConnectionByTool();
        }
      },
      fillMaxSize: true,
      padding: 16,
      spacing: 16
    },
    rootChildren
  );
}
