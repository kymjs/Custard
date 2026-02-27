/**
 * Compose-like DSL type definitions for toolpkg ui_modules runtime="compose_dsl".
 */

export type ComposeTextStyle =
  | "headlineSmall"
  | "headlineMedium"
  | "titleLarge"
  | "titleMedium"
  | "bodyLarge"
  | "bodyMedium"
  | "bodySmall"
  | "labelLarge"
  | "labelMedium"
  | "labelSmall";

export type ComposeArrangement =
  | "start"
  | "center"
  | "end"
  | "spaceBetween"
  | "spaceAround"
  | "spaceEvenly";

export type ComposeAlignment = "start" | "center" | "end";

export interface ComposeCommonProps {
  key?: string;
  onLoad?: () => void | Promise<void>;
  weight?: number;
  width?: number;
  height?: number;
  padding?: number;
  paddingHorizontal?: number;
  paddingVertical?: number;
  spacing?: number;
  fillMaxWidth?: boolean;
  fillMaxSize?: boolean;
}

export interface ColumnProps extends ComposeCommonProps {
  horizontalAlignment?: ComposeAlignment;
  verticalArrangement?: ComposeArrangement;
}

export interface RowProps extends ComposeCommonProps {
  horizontalArrangement?: ComposeArrangement;
  verticalAlignment?: ComposeAlignment;
}

export interface BoxProps extends ComposeCommonProps {
  contentAlignment?: ComposeAlignment;
}

export interface SpacerProps {
  width?: number;
  height?: number;
}

export interface TextProps extends ComposeCommonProps {
  text: string;
  style?: ComposeTextStyle;
  color?: string;
  fontWeight?: string;
  maxLines?: number;
  weight?: number;
}

export interface TextFieldProps extends ComposeCommonProps {
  label?: string;
  placeholder?: string;
  value: string;
  onValueChange: (value: string) => void;
  singleLine?: boolean;
  minLines?: number;
}

export interface SwitchProps extends ComposeCommonProps {
  checked: boolean;
  onCheckedChange: (checked: boolean) => void;
  enabled?: boolean;
}

export interface CheckboxProps extends ComposeCommonProps {
  checked: boolean;
  onCheckedChange: (checked: boolean) => void;
  enabled?: boolean;
}

export interface ButtonProps extends ComposeCommonProps {
  text?: string;
  enabled?: boolean;
  onClick: () => void | Promise<void>;
}

export interface IconButtonProps extends ComposeCommonProps {
  icon: string;
  enabled?: boolean;
  onClick: () => void | Promise<void>;
}

export interface CardProps extends ComposeCommonProps {
  containerColor?: string;
  contentColor?: string;
}

export interface IconProps extends ComposeCommonProps {
  name?: string;
  tint?: string;
}

export interface LazyColumnProps extends ComposeCommonProps {
  spacing?: number;
}

export interface LinearProgressIndicatorProps extends ComposeCommonProps {
  progress?: number;
}

export interface CircularProgressIndicatorProps extends ComposeCommonProps {
  strokeWidth?: number;
  color?: string;
}

export interface SnackbarHostProps extends ComposeCommonProps {}

export interface ComposeNode {
  type: string;
  props?: Record<string, unknown>;
  children?: ComposeNode[];
}

export interface ComposeTemplateValues {
  [key: string]: string | number | boolean | null | undefined;
}

export interface ComposeUiModuleSpec {
  id?: string;
  runtime?: string;
  [key: string]: unknown;
}

export interface ComposeToolCandidateResolveRequest {
  packageName?: string;
  subpackageId?: string;
  toolName: string;
  extraCandidates?: string[];
  preferImported?: boolean;
}

export interface ComposeToolCandidateCallResult<T = unknown> {
  toolName?: string;
  result?: T;
  error?: string;
}

export interface ComposeToolCallConfig {
  type?: string;
  name: string;
  params?: Record<string, unknown>;
}

export interface ComposeResolveToolNameRequest {
  packageName?: string;
  subpackageId?: string;
  toolName: string;
  preferImported?: boolean;
}

export interface ComposeDslContext {
  useState<T>(key: string, initialValue: T): [T, (value: T) => void];
  useMemo<T>(key: string, factory: () => T, deps?: unknown[]): T;

  callTool<T = any>(toolName: string, params?: Record<string, unknown>): Promise<T>;
  getEnv(key: string): string | undefined;
  setEnv(key: string, value: string): Promise<void> | void;
  readResource(key: string): Promise<string | Uint8Array>;
  navigate(route: string, args?: Record<string, unknown>): Promise<void> | void;
  showToast(message: string): Promise<void> | void;
  reportError(error: unknown): Promise<void> | void;

  /**
   * Returns runtime module spec parsed from toolpkg ui_modules entry.
   */
  getModuleSpec?<TSpec extends ComposeUiModuleSpec = ComposeUiModuleSpec>(): TSpec;

  /**
   * Runtime identity of current compose_dsl module.
   */
  getCurrentPackageName?(): string | undefined;
  getCurrentToolPkgId?(): string | undefined;
  getCurrentUiModuleId?(): string | undefined;

  /**
   * Returns current UI locale from host, e.g. zh-CN / en-US.
   */
  getLocale?(): string | undefined;

  /**
   * Formats template text like "failed: {error}" with provided values.
   */
  formatTemplate?(template: string, values: ComposeTemplateValues): string;

  /**
   * Lets host resolve tool candidates for subpackage/container mapping.
   */
  resolveToolCandidates?(
    request: ComposeToolCandidateResolveRequest
  ): Promise<string[]> | string[];

  /**
   * Calls tool candidates in order until one succeeds; host decides success policy.
   */
  callToolCandidates?<T = unknown>(
    toolNames: string[],
    params?: Record<string, unknown>
  ): Promise<ComposeToolCandidateCallResult<T>>;

  /**
   * Batch environment writes; host may implement atomically.
   */
  setEnvs?(values: Record<string, string>): Promise<void> | void;

  /**
   * Optional toolCall-compatible bridge so compose_dsl script can use package-tool style calls.
   */
  toolCall?<T = unknown>(toolName: string, toolParams?: Record<string, unknown>): Promise<T>;
  toolCall?<T = unknown>(
    toolType: string,
    toolName: string,
    toolParams?: Record<string, unknown>
  ): Promise<T>;
  toolCall?<T = unknown>(config: ComposeToolCallConfig): Promise<T>;

  /**
   * Package-manager bridge methods.
   */
  isPackageImported?(packageName: string): Promise<boolean> | boolean;
  importPackage?(packageName: string): Promise<string> | string;
  removePackage?(packageName: string): Promise<string> | string;
  usePackage?(packageName: string): Promise<string> | string;
  listImportedPackages?(): Promise<string[]> | string[];

  /**
   * Resolve runtime tool name for a package/subpackage before calling it directly.
   */
  resolveToolName?(request: ComposeResolveToolNameRequest): Promise<string> | string;

  Column(props: ColumnProps, children?: ComposeNode[]): ComposeNode;
  Row(props: RowProps, children?: ComposeNode[]): ComposeNode;
  Box(props: BoxProps, children?: ComposeNode[]): ComposeNode;
  Spacer(props?: SpacerProps): ComposeNode;

  Text(props: TextProps): ComposeNode;
  TextField(props: TextFieldProps): ComposeNode;
  Switch(props: SwitchProps): ComposeNode;
  Checkbox(props: CheckboxProps): ComposeNode;

  Button(props: ButtonProps, children?: ComposeNode[]): ComposeNode;
  IconButton(props: IconButtonProps): ComposeNode;
  Card(props: CardProps, children?: ComposeNode[]): ComposeNode;
  Icon(props: IconProps): ComposeNode;

  LazyColumn(props: LazyColumnProps, children?: ComposeNode[]): ComposeNode;
  LinearProgressIndicator(props?: LinearProgressIndicatorProps): ComposeNode;
  CircularProgressIndicator(props?: CircularProgressIndicatorProps): ComposeNode;
  SnackbarHost(props?: SnackbarHostProps): ComposeNode;
}

export type ComposeDslScreen = (ctx: ComposeDslContext) => ComposeNode | Promise<ComposeNode>;
