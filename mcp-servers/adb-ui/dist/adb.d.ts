/**
 * Run adb shell command. Assumes adb is in PATH and device is connected.
 */
export declare function adbShell(cmd: string): {
    stdout: string;
    stderr: string;
    success: boolean;
};
/**
 * Dump UI hierarchy to /sdcard/window_dump.xml and return XML string.
 * @param displayId - Optional display id (e.g. "0", "1" for secondary screen).
 */
export declare function dumpUiTree(displayId?: string): {
    xml: string;
    error?: string;
};
/**
 * Parse bounds string "[left,top][right,bottom]" to center { x, y }.
 */
export declare function boundsToCenter(bounds: string): {
    x: number;
    y: number;
} | null;
export interface UINodeAttr {
    class?: string;
    text?: string;
    "content-desc"?: string;
    "resource-id"?: string;
    bounds?: string;
    clickable?: string;
}
/**
 * Simple regex-based extraction of <node ... /> attributes from uiautomator XML.
 * Does not handle nested XML; we only need top-level node attributes for matching.
 */
export declare function parseNodeAttrs(nodeTag: string): UINodeAttr;
/**
 * Find all <node ...> opening tags in XML (flat, no nesting structure).
 */
export declare function findAllNodeTags(xml: string): RegExpMatchArray[];
/**
 * Match a node tag string against optional resourceId, className, contentDesc.
 * partialMatch: if true, attribute value may be a substring.
 */
export declare function nodeMatches(attrs: UINodeAttr, options: {
    resourceId?: string;
    className?: string;
    contentDesc?: string;
    text?: string;
    partialMatch?: boolean;
}): boolean;
/**
 * Execute tap at (x, y).
 */
export declare function tap(x: number, y: number, displayId?: string): {
    success: boolean;
    error?: string;
};
