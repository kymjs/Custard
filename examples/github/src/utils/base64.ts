export function safeAtobBase64(b64: string): string {
    const cleaned = String(b64 || '').replace(/\s+/g, '');
    return atob(cleaned);
}

export function safeBtoaBase64(text: string): string {
    return btoa(String(text ?? ''));
}
