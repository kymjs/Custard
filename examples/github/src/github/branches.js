"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.createBranch = createBranch;
const api_1 = require("./api");
const repos_1 = require("./repos");
async function getBranchHeadSha(params) {
    var _a;
    const url = (0, api_1.buildUrl)(`/repos/${encodeURIComponent(params.owner)}/${encodeURIComponent(params.repo)}/git/ref/heads/${encodeURIComponent(params.branch)}`);
    const data = await (0, api_1.requestJson)({ method: 'GET', url });
    const sha = (_a = data === null || data === void 0 ? void 0 : data.object) === null || _a === void 0 ? void 0 : _a.sha;
    if (!sha) {
        throw new Error(`Cannot resolve branch sha for ${params.branch}`);
    }
    return sha;
}
async function createBranch(params) {
    var _a, _b;
    const token = (0, api_1.getToken)();
    if (!token) {
        throw new Error('GITHUB_TOKEN is required for create_branch.');
    }
    const fromBranch = (_a = params.from_branch) !== null && _a !== void 0 ? _a : String(((_b = (await (0, repos_1.getRepository)({ owner: params.owner, repo: params.repo }))) === null || _b === void 0 ? void 0 : _b.default_branch) || 'main');
    const sha = await getBranchHeadSha({ owner: params.owner, repo: params.repo, branch: fromBranch });
    const url = (0, api_1.buildUrl)(`/repos/${encodeURIComponent(params.owner)}/${encodeURIComponent(params.repo)}/git/refs`);
    return (0, api_1.requestJson)({
        method: 'POST',
        url,
        body: {
            ref: `refs/heads/${params.new_branch}`,
            sha
        }
    });
}
