"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.searchRepositories = searchRepositories;
exports.getRepository = getRepository;
const api_1 = require("./api");
async function searchRepositories(params) {
    var _a, _b;
    const page = (_a = params.page) !== null && _a !== void 0 ? _a : 1;
    const perPage = (_b = params.per_page) !== null && _b !== void 0 ? _b : 30;
    const url = (0, api_1.buildUrl)('/search/repositories', {
        q: params.query,
        sort: params.sort,
        order: params.order,
        page,
        per_page: perPage
    });
    return (0, api_1.requestJson)({ method: 'GET', url });
}
async function getRepository(params) {
    const url = (0, api_1.buildUrl)(`/repos/${encodeURIComponent(params.owner)}/${encodeURIComponent(params.repo)}`);
    return (0, api_1.requestJson)({ method: 'GET', url });
}
