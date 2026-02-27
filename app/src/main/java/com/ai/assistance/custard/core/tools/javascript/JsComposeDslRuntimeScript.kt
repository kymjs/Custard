package com.ai.assistance.custard.core.tools.javascript

internal fun buildComposeDslRuntimeWrappedScript(script: String): String {
    return """
        $script

        (function() {
            function __custard_is_promise(__value) {
                return !!(__value && typeof __value.then === 'function');
            }

            function __custard_wrap_compose_response(__bundle, __tree) {
                return {
                    tree: __tree,
                    state: __bundle.state,
                    memo: __bundle.memo
                };
            }

            function __custard_build_compose_response(__bundle, __entry) {
                var __tree = __entry(__bundle.ctx);
                if (__custard_is_promise(__tree)) {
                    return __tree.then(function(__resolvedTree) {
                        return __custard_wrap_compose_response(__bundle, __resolvedTree);
                    });
                }
                return __custard_wrap_compose_response(__bundle, __tree);
            }

            function __custardResolveComposeEntry() {
                try {
                    if (typeof module !== 'undefined' && module && module.exports) {
                        if (typeof module.exports.default === 'function') {
                            return module.exports.default;
                        }
                        if (typeof module.exports.Screen === 'function') {
                            return module.exports.Screen;
                        }
                    }
                    if (typeof exports !== 'undefined' && exports) {
                        if (typeof exports.default === 'function') {
                            return exports.default;
                        }
                        if (typeof exports.Screen === 'function') {
                            return exports.Screen;
                        }
                    }
                    if (typeof window !== 'undefined') {
                        if (typeof window.default === 'function') {
                            return window.default;
                        }
                        if (typeof window.Screen === 'function') {
                            return window.Screen;
                        }
                    }
                } catch (e) {
                    console.error('resolve compose entry failed:', e);
                }
                return null;
            }

            function __custard_render_compose_dsl(__runtimeOptions) {
                if (typeof CustardComposeDslRuntime === 'undefined') {
                    throw new Error('CustardComposeDslRuntime bridge is not initialized');
                }
                var __bundle = CustardComposeDslRuntime.createContext(__runtimeOptions || {});
                var __entry = __custardResolveComposeEntry();
                if (typeof __entry !== 'function') {
                    throw new Error(
                        'compose_dsl entry function not found, expected default export or Screen function'
                    );
                }
                if (typeof window !== 'undefined') {
                    window.__custard_compose_bundle = __bundle;
                    window.__custard_compose_entry = __entry;
                }
                return __custard_build_compose_response(__bundle, __entry);
            }

            function __custard_dispatch_compose_dsl_action(__actionRequest) {
                if (typeof window === 'undefined') {
                    throw new Error('compose action dispatch requires window runtime');
                }
                var __bundle = window.__custard_compose_bundle;
                var __entry = window.__custard_compose_entry;
                if (!__bundle || typeof __entry !== 'function') {
                    throw new Error('compose_dsl runtime is not initialized, render first');
                }
                if (typeof __bundle.invokeAction !== 'function') {
                    throw new Error('compose_dsl runtime action bridge is not available');
                }

                var __request =
                    __actionRequest && typeof __actionRequest === 'object'
                        ? __actionRequest
                        : {};
                var __actionId = String(
                    __request.__action_id || __request.actionId || ''
                ).trim();
                if (!__actionId) {
                    throw new Error('compose action id is required');
                }

                var __payload =
                    Object.prototype.hasOwnProperty.call(__request, '__action_payload')
                        ? __request.__action_payload
                        : __request.payload;

                function __custard_send_intermediate_result(__value) {
                    if (
                        typeof NativeInterface === 'undefined' ||
                        !NativeInterface ||
                        typeof NativeInterface.sendIntermediateResult !== 'function'
                    ) {
                        return;
                    }
                    NativeInterface.sendIntermediateResult(JSON.stringify(__value));
                }

                var __actionSettled = false;
                var __intermediateRenderQueued = false;
                var __intermediateRenderInFlight = false;
                var __unsubscribeStateChange = null;

                function __custard_finalize_action() {
                    __actionSettled = true;
                    if (typeof __unsubscribeStateChange === 'function') {
                        try {
                            __unsubscribeStateChange();
                        } catch (__unsubscribeError) {
                        }
                        __unsubscribeStateChange = null;
                    }
                }

                function __custard_render_and_send_intermediate() {
                    if (__actionSettled) {
                        return null;
                    }
                    try {
                        var __intermediateResponse = __custard_build_compose_response(__bundle, __entry);
                        if (__custard_is_promise(__intermediateResponse)) {
                            return __intermediateResponse.then(function(__resolvedIntermediate) {
                                if (!__actionSettled) {
                                    __custard_send_intermediate_result(__resolvedIntermediate);
                                }
                            });
                        }
                        __custard_send_intermediate_result(__intermediateResponse);
                    } catch (__intermediateError) {
                        try {
                            console.warn('compose intermediate render failed:', __intermediateError);
                        } catch (__ignore) {
                        }
                    }
                    return null;
                }

                function __custard_process_intermediate_queue() {
                    if (__actionSettled || __intermediateRenderInFlight || !__intermediateRenderQueued) {
                        return;
                    }
                    __intermediateRenderQueued = false;
                    __intermediateRenderInFlight = true;
                    var __renderResult = __custard_render_and_send_intermediate();
                    if (__custard_is_promise(__renderResult)) {
                        __renderResult.then(
                            function() {},
                            function() {}
                        ).then(function() {
                            __intermediateRenderInFlight = false;
                            if (__intermediateRenderQueued && !__actionSettled) {
                                __custard_process_intermediate_queue();
                            }
                        });
                        return;
                    }
                    __intermediateRenderInFlight = false;
                    if (__intermediateRenderQueued && !__actionSettled) {
                        __custard_process_intermediate_queue();
                    }
                }

                function __custard_schedule_intermediate_render() {
                    if (__actionSettled) {
                        return;
                    }
                    __intermediateRenderQueued = true;
                    Promise.resolve().then(function() {
                        __custard_process_intermediate_queue();
                    });
                }

                if (typeof __bundle.subscribeStateChange === 'function') {
                    __unsubscribeStateChange = __bundle.subscribeStateChange(function() {
                        __custard_schedule_intermediate_render();
                    });
                }

                var __maybePromise;
                try {
                    __maybePromise = __bundle.invokeAction(__actionId, __payload);
                } catch (__actionError) {
                    __custard_finalize_action();
                    throw __actionError;
                }
                if (__maybePromise && typeof __maybePromise.then === 'function') {
                    // For async actions, schedule a render checkpoint immediately.
                    // Additional state updates during await phases are pushed by state-change listeners.
                    __custard_schedule_intermediate_render();
                    return __maybePromise.then(function() {
                        __custard_finalize_action();
                        return __custard_build_compose_response(__bundle, __entry);
                    }, function(__actionError) {
                        __custard_finalize_action();
                        throw __actionError;
                    });
                }
                __custard_finalize_action();
                return __custard_build_compose_response(__bundle, __entry);
            }

            if (typeof exports !== 'undefined' && exports) {
                exports.__custard_render_compose_dsl = __custard_render_compose_dsl;
                exports.__custard_dispatch_compose_dsl_action =
                    __custard_dispatch_compose_dsl_action;
            }
            if (typeof module !== 'undefined' && module && module.exports) {
                module.exports.__custard_render_compose_dsl = __custard_render_compose_dsl;
                module.exports.__custard_dispatch_compose_dsl_action =
                    __custard_dispatch_compose_dsl_action;
            }
            if (typeof window !== 'undefined') {
                window.__custard_render_compose_dsl = __custard_render_compose_dsl;
                window.__custard_dispatch_compose_dsl_action =
                    __custard_dispatch_compose_dsl_action;
            }
        })();
    """.trimIndent()
}
