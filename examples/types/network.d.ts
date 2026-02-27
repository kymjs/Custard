/**
 * Network operation type definitions for Assistance Package Tools
 */

import { HttpResponseData, VisitWebResultData, StringResultData } from './results';

/**
 * Network operations namespace
 */
export namespace Net {
    /**
     * Perform HTTP GET request
     * @param url - URL to request
     */
    function httpGet(url: string): Promise<HttpResponseData>;

    /**
     * Perform HTTP POST request
     * @param url - URL to request
     * @param data - Data to post
     */
    function httpPost(url: string, body: string | object): Promise<HttpResponseData>;

    /**
     * Visit a webpage and extract its content
     * @param urlOrParams - URL to visit, or an object with visit parameters.
     */
    function visit(urlOrParams: string | {
        url?: string;
        visit_key?: string;
        link_number?: number;
        include_image_links?: boolean;
        headers?: Record<string, string>;
        user_agent_preset?: string;
        user_agent?: string;
    }): Promise<VisitWebResultData>;

    /**
     * Start a persistent web session (floating window WebView).
     * Returns StringResultData whose `value` is a JSON string payload.
     */
    function startWeb(options?: {
        url?: string;
        headers?: Record<string, string> | string;
        user_agent?: string;
        session_name?: string;
    }): Promise<StringResultData>;

    /**
     * Stop one web session or all web sessions.
     * Returns StringResultData whose `value` is a JSON string payload.
     */
    function stopWeb(sessionIdOrOptions?: string | {
        session_id?: string;
        close_all?: boolean;
    }): Promise<StringResultData>;

    /**
     * Navigate a web session to a target URL.
     */
    function webNavigate(
        sessionId: string | undefined,
        url: string,
        headers?: Record<string, string> | string
    ): Promise<StringResultData>;

    /**
     * Evaluate JavaScript in a web session.
     */
    function webEval(
        sessionId: string | undefined,
        script: string,
        timeoutMs?: number
    ): Promise<StringResultData>;

    /**
     * Click an element by snapshot ref.
     * Only accepts one options object.
     */
    function webClick(options: {
        session_id?: string;
        ref: string;
        element?: string;
        button?: 'left' | 'right' | 'middle';
        modifiers?: Array<'Alt' | 'Control' | 'ControlOrMeta' | 'Meta' | 'Shift'>;
        doubleClick?: boolean;
    }): Promise<StringResultData>;

    /**
     * Fill an input by CSS selector.
     */
    function webFill(
        sessionId: string | undefined,
        selector: string,
        value: string
    ): Promise<StringResultData>;

    /**
     * Wait for page ready or selector appearance.
     */
    function webWaitFor(
        sessionId: string | undefined,
        selector?: string,
        timeoutMs?: number
    ): Promise<StringResultData>;

    /**
     * Capture a text snapshot of current page.
     */
    function webSnapshot(
        sessionId: string | undefined,
        options?: {
            include_links?: boolean;
            include_images?: boolean;
        }
    ): Promise<StringResultData>;

    /**
     * Resolve an active file chooser in a web session.
     * If `paths` is omitted, the file chooser is cancelled.
     */
    function webFileUpload(
        sessionId: string | undefined,
        paths?: string[]
    ): Promise<StringResultData>;

    /**
     * Enhanced HTTP request with flexible options
     * @param options - HTTP request options
     */
    function http(options: {
        url: string;
        method?: 'GET' | 'POST' | 'PUT' | 'DELETE' | 'PATCH' | 'HEAD' | 'OPTIONS';
        headers?: Record<string, string>;
        body?: string | object;
        connect_timeout?: number;
        read_timeout?: number;
        follow_redirects?: boolean;
        responseType?: 'text' | 'json' | 'arraybuffer' | 'blob';
        validateStatus?: boolean;
    }): Promise<HttpResponseData>;

    /**
     * Upload file using multipart request
     * @param options - Upload options
     */
    function uploadFile(options: {
        url: string;
        method?: 'POST' | 'PUT';
        headers?: Record<string, string>;
        form_data?: Record<string, string>;
        files: {
            field_name: string;
            file_path: string;
            content_type?: string;
            file_name?: string;
        }[];
    }): Promise<HttpResponseData>;

    /**
     * Cookie management interface
     */
    interface CookieManager {
        /**
         * Get cookies for a domain
         * @param domain - Domain to get cookies for
         */
        get(domain: string): Promise<HttpResponseData>;

        /**
         * Set cookies for a domain
         * @param domain - Domain to set cookies for
         * @param cookies - Cookies to set (can be string or object)
         */
        set(domain: string, cookies: string | Record<string, string>): Promise<HttpResponseData>;

        /**
         * Clear cookies for a domain
         * @param domain - Domain to clear cookies for
         */
        clear(domain?: string): Promise<HttpResponseData>;
    }

    /**
     * Cookie management
     */
    const cookies: CookieManager;
}
