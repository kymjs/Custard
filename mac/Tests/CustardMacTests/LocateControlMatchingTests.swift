import XCTest
@testable import CustardMac

final class LocateControlMatchingTests: XCTestCase {
    func testExtractMatchTermsFromQuotedDescription() {
        let terms = LocateControlTool.extractMatchTerms(
            from: "联系人列表第一项\"张涛\"（排除昵称行）"
        )
        XCTAssertEqual(terms, ["张涛"])
    }

    func testExtractJSONObjectFromThinkingSuffix() {
        let raw = """
        <thinking>estimate position</thinking>
        {"x": 720, "y": 368}
        """
        let object = LocateControlTool.extractJSONObject(from: raw)
        XCTAssertEqual(object?["x"] as? Int, 720)
        XCTAssertEqual(object?["y"] as? Int, 368)
    }

    func testExtractJSONObjectPrefersCoordinateObjectOverThinkingWrapper() {
        let raw = """
        {"error":"not_found"}
        {"x": 346, "y": 864}
        """
        let object = LocateControlTool.extractJSONObject(from: raw)
        XCTAssertEqual(object?["x"] as? Int, 346)
        XCTAssertEqual(object?["y"] as? Int, 864)
    }
}
