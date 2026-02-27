"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.safeAtobBase64 = safeAtobBase64;
exports.safeBtoaBase64 = safeBtoaBase64;
function safeAtobBase64(b64) {
    const cleaned = String(b64 || '').replace(/\s+/g, '');
    return atob(cleaned);
}
function safeBtoaBase64(text) {
    return btoa(String(text !== null && text !== void 0 ? text : ''));
}
