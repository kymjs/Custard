import AppKit
import Foundation
import ImageIO

enum LocateControlTool {
    private static let notFoundHint =
        "请给出控件在截图中更详细的描述（相对位置、文案原文、形状颜色等）"

    static func locate(
        imagePath: String,
        description: String,
        uiTreeText: String? = nil,
        screenWidth: Int? = nil,
        screenHeight: Int? = nil
    ) async -> String {
        let trimmedPath = imagePath.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedDescription = description.trimmingCharacters(in: .whitespacesAndNewlines)
        let uiTreeLines = uiTreeText?.split(whereSeparator: \.isNewline).count ?? 0
        Logger.chat(
            "locate_control: begin desc=\(preview(trimmedDescription)) "
            + "image=\(trimmedPath) uiTreeLines=\(uiTreeLines) "
            + "screen=\(screenWidth.map(String.init) ?? "?")x\(screenHeight.map(String.init) ?? "?")"
        )

        guard !trimmedPath.isEmpty else {
            Logger.chat("locate_control: error invalid_path empty image_path")
            return errorJSON(code: "invalid_path", message: "image_path 不能为空。")
        }
        guard !trimmedDescription.isEmpty else {
            Logger.chat("locate_control: error not_found empty description")
            return errorJSON(
                code: "not_found",
                message: "description 不能为空。",
                hint: notFoundHint
            )
        }

        if let uiMatch = matchUiTree(
            description: trimmedDescription,
            uiTreeText: uiTreeText,
            screenWidth: screenWidth,
            screenHeight: screenHeight
        ) {
            Logger.chat(
                "locate_control: ui_tree hit label=\(preview(uiMatch.label)) "
                + "bounds=[\(uiMatch.left),\(uiMatch.top)][\(uiMatch.right),\(uiMatch.bottom)] "
                + "px=(\(uiMatch.xPercent),\(uiMatch.yPercent)) hit_mode=\(uiMatch.hitMode) "
                + "score=\(uiMatch.score)"
            )
            return successJSON(
                xPercent: uiMatch.xPercent,
                yPercent: uiMatch.yPercent,
                source: "ui_tree",
                imageWidth: screenWidth,
                imageHeight: screenHeight,
                extra: [
                    "matched_label": uiMatch.label,
                    "bounds": "[\(uiMatch.left),\(uiMatch.top)][\(uiMatch.right),\(uiMatch.bottom)]",
                    "hit_mode": uiMatch.hitMode
                ]
            )
        }

        Logger.chat("locate_control: ui_tree miss, falling back to vision")

        let url = URL(fileURLWithPath: trimmedPath)
        guard FileManager.default.fileExists(atPath: url.path) else {
            Logger.chat("locate_control: error invalid_path missing file")
            return errorJSON(code: "invalid_path", message: "截图文件不存在：\(trimmedPath)")
        }

        guard
            let imageData = try? Data(contentsOf: url),
            !imageData.isEmpty
        else {
            Logger.chat("locate_control: error invalid_image unreadable")
            return errorJSON(code: "invalid_image", message: "无法读取截图文件：\(trimmedPath)")
        }

        guard let (width, height) = imagePixelSize(data: imageData), width > 0, height > 0 else {
            Logger.chat("locate_control: error invalid_image bad size")
            return errorJSON(code: "invalid_image", message: "无法解析截图尺寸：\(trimmedPath)")
        }

        let visionData = overlayPixelGrid(pngData: imageData) ?? imageData
        let base64 = visionData.base64EncodedString()

        let raw: String
        do {
            raw = try await LLMService.locateControlVision(
                imageBase64: base64,
                imageWidth: width,
                imageHeight: height,
                description: trimmedDescription
            )
        } catch {
            Logger.chat("locate_control: vision model_error \(error.localizedDescription)")
            return errorJSON(
                code: "model_error",
                message: error.localizedDescription
            )
        }

        Logger.chat("locate_control: vision raw \(preview(raw, limit: 240))")
        let result = interpretVisionResponse(
            raw,
            imageWidth: width,
            imageHeight: height,
            description: trimmedDescription,
            imageData: imageData
        )
        Logger.chat("locate_control: vision result \(preview(result, limit: 240))")
        return result
    }

    // MARK: - UI tree

    private struct UiMatch {
        let xPercent: Double
        let yPercent: Double
        let left: Int
        let top: Int
        let right: Int
        let bottom: Int
        let label: String
        let hitMode: String
        let score: Int
    }

    private static func isCheckboxLikeDescription(_ description: String) -> Bool {
        let q = normalize(description)
        let keywords = [
            "勾选", "复选", "选框", "checkbox", "check", "radio",
            "协议同意", "我已阅读", "已阅读并同意", "同意协议", "privacy"
        ]
        return keywords.contains { q.contains(normalize($0)) }
    }

    private static func hitPoint(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        checkboxLike: Bool
    ) -> (x: Double, y: Double, mode: String) {
        let width = Double(right - left)
        let height = Double(bottom - top)
        let cy = Double(top) + height / 2.0
        if checkboxLike, width > 0 {
            let inset = min(max(width * 0.12, 12.0), width * 0.35)
            return (Double(left) + inset, cy, "checkbox_left")
        }
        return (Double(left) + width / 2.0, cy, "center")
    }

    private static func matchUiTree(
        description: String,
        uiTreeText: String?,
        screenWidth: Int?,
        screenHeight: Int?
    ) -> UiMatch? {
        guard
            let uiTreeText, !uiTreeText.isEmpty,
            let screenWidth, screenWidth > 0,
            let screenHeight, screenHeight > 0
        else {
            Logger.chat(
                "locate_control: ui_tree unavailable "
                + "textEmpty=\(uiTreeText?.isEmpty ?? true) "
                + "screen=\(screenWidth.map(String.init) ?? "?")x\(screenHeight.map(String.init) ?? "?")"
            )
            return nil
        }

        let query = normalize(description)
        guard !query.isEmpty else { return nil }
        let terms = extractMatchTerms(from: description)
        let checkboxLike = isCheckboxLikeDescription(description)

        let lines = uiTreeText.split(whereSeparator: \.isNewline).map(String.init)
        var best: (score: Int, left: Int, top: Int, right: Int, bottom: Int, label: String, clickable: Bool)?

        for line in lines {
            guard let bounds = parseBounds(from: line) else { continue }
            var candidates = [normalize(line)]
            if let quoted = extractQuoted(from: line) {
                candidates.append(normalize(quoted))
            }
            if let resourceId = extractResourceId(from: line) {
                candidates.append(normalize(resourceId))
            }
            let score = maxMatchScore(query: query, candidates: candidates, terms: terms)
            guard score >= 50 else { continue }

            let label = extractQuoted(from: line) ?? extractResourceId(from: line) ?? description
            let clickable = line.contains("[可点击]")
            let area = (bounds.2 - bounds.0) * (bounds.3 - bounds.1)
            if let current = best {
                if shouldPreferCandidate(
                    currentScore: current.score,
                    currentTop: current.top,
                    currentArea: (current.right - current.left) * (current.bottom - current.top),
                    currentClickable: current.clickable,
                    candidateScore: score,
                    candidateTop: bounds.1,
                    candidateArea: area,
                    candidateClickable: clickable
                ) {
                    best = (score, bounds.0, bounds.1, bounds.2, bounds.3, label, clickable)
                }
            } else {
                best = (score, bounds.0, bounds.1, bounds.2, bounds.3, label, clickable)
            }
        }

        guard let best, best.score >= 50 else {
            Logger.chat("locate_control: ui_tree no match above threshold query=\(preview(description))")
            return nil
        }

        let point = hitPoint(
            left: best.left,
            top: best.top,
            right: best.right,
            bottom: best.bottom,
            checkboxLike: checkboxLike
        )
        let xPercent = point.x / Double(screenWidth) * 100.0
        let yPercent = point.y / Double(screenHeight) * 100.0
        guard (0...100).contains(xPercent), (0...100).contains(yPercent) else { return nil }

        return UiMatch(
            xPercent: roundPercent(xPercent),
            yPercent: roundPercent(yPercent),
            left: best.left,
            top: best.top,
            right: best.right,
            bottom: best.bottom,
            label: best.label,
            hitMode: point.mode,
            score: best.score
        )
    }

    private static func preview(_ text: String, limit: Int = 80) -> String {
        let compact = text.replacingOccurrences(of: "\n", with: " ")
        if compact.count <= limit { return compact }
        return String(compact.prefix(limit)) + "…"
    }

    private static func shouldPreferCandidate(
        currentScore: Int,
        currentTop: Int,
        currentArea: Int,
        currentClickable: Bool,
        candidateScore: Int,
        candidateTop: Int,
        candidateArea: Int,
        candidateClickable: Bool
    ) -> Bool {
        if candidateScore > currentScore { return true }
        if candidateScore < currentScore { return false }
        if candidateClickable && !currentClickable { return true }
        if currentClickable && !candidateClickable { return false }
        if candidateTop < currentTop { return true }
        if candidateTop > currentTop { return false }
        return candidateArea > 0 && candidateArea < currentArea
    }

    static func extractMatchTerms(from description: String) -> [String] {
        guard let regex = try? NSRegularExpression(pattern: #""([^"]+)""#) else { return [] }
        let range = NSRange(description.startIndex..<description.endIndex, in: description)
        var terms: [String] = []
        for match in regex.matches(in: description, range: range) {
            guard match.numberOfRanges > 1,
                  let valueRange = Range(match.range(at: 1), in: description) else { continue }
            let value = String(description[valueRange]).trimmingCharacters(in: .whitespacesAndNewlines)
            if !value.isEmpty {
                terms.append(value)
            }
        }
        return terms
    }

    private static func maxMatchScore(
        query: String,
        candidates: [String],
        terms: [String] = []
    ) -> Int {
        var best = 0
        let normalizedTerms = terms.map(normalize).filter { !$0.isEmpty }
        let nicknameToken = normalize("昵称")

        for raw in candidates {
            let candidate = normalize(raw)
            guard !candidate.isEmpty else { continue }

            if candidate.contains(nicknameToken),
               !normalizedTerms.contains(where: { $0.contains(nicknameToken) }) {
                continue
            }

            if !normalizedTerms.isEmpty {
                for term in normalizedTerms {
                    if candidate == term {
                        best = max(best, 100)
                    } else if candidate.contains(term) {
                        best = max(best, 90)
                    }
                }
            }

            if candidate == query { best = max(best, 100) }
            else if candidate.contains(query) || query.contains(candidate) { best = max(best, 80) }
            else if tokenOverlap(query, candidate) { best = max(best, 60) }
            else if candidate.contains(query.filter { !$0.isWhitespace }) { best = max(best, 55) }
        }
        return best
    }

    private static func tokenOverlap(_ a: String, _ b: String) -> Bool {
        let tokensA = Set(a.split { !$0.isLetter && !$0.isNumber }.map(String.init).filter { $0.count >= 2 })
        let tokensB = Set(b.split { !$0.isLetter && !$0.isNumber }.map(String.init).filter { $0.count >= 2 })
        return !tokensA.isEmpty && !tokensA.isDisjoint(with: tokensB)
    }

    private static func normalize(_ text: String) -> String {
        text
            .lowercased()
            .replacingOccurrences(of: "\\s+", with: "", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func parseBounds(from line: String) -> (Int, Int, Int, Int)? {
        let pattern = #"@ \[(\d+),(\d+)\]\[(\d+),(\d+)\]"#
        guard let regex = try? NSRegularExpression(pattern: pattern) else { return nil }
        let range = NSRange(line.startIndex..<line.endIndex, in: line)
        guard
            let match = regex.firstMatch(in: line, range: range),
            match.numberOfRanges == 5,
            let r1 = Range(match.range(at: 1), in: line),
            let r2 = Range(match.range(at: 2), in: line),
            let r3 = Range(match.range(at: 3), in: line),
            let r4 = Range(match.range(at: 4), in: line),
            let left = Int(line[r1]),
            let top = Int(line[r2]),
            let right = Int(line[r3]),
            let bottom = Int(line[r4]),
            right > left, bottom > top
        else { return nil }
        return (left, top, right, bottom)
    }

    private static func extractQuoted(from line: String) -> String? {
        guard let regex = try? NSRegularExpression(pattern: #"\"([^\"]+)\""#) else { return nil }
        let range = NSRange(line.startIndex..<line.endIndex, in: line)
        guard
            let match = regex.firstMatch(in: line, range: range),
            match.numberOfRanges > 1,
            let valueRange = Range(match.range(at: 1), in: line)
        else { return nil }
        let value = String(line[valueRange]).trimmingCharacters(in: .whitespacesAndNewlines)
        return value.isEmpty ? nil : value
    }

    private static func extractResourceId(from line: String) -> String? {
        guard let regex = try? NSRegularExpression(pattern: #"id=([^\s]+)"#) else { return nil }
        let range = NSRange(line.startIndex..<line.endIndex, in: line)
        guard
            let match = regex.firstMatch(in: line, range: range),
            match.numberOfRanges > 1,
            let valueRange = Range(match.range(at: 1), in: line)
        else { return nil }
        return String(line[valueRange])
    }

    // MARK: - Vision parse

    private static func interpretVisionResponse(
        _ raw: String,
        imageWidth: Int,
        imageHeight: Int,
        description: String,
        imageData: Data
    ) -> String {
        guard let object = extractJSONObject(from: raw) else {
            return errorJSON(
                code: "not_found",
                message: "模型未返回可解析的定位 JSON。",
                hint: notFoundHint
            )
        }

        if let errorCode = object["error"] as? String, !errorCode.isEmpty {
            let message = object["message"] as? String ?? "未能定位到指定控件。"
            let hint = object["hint"] as? String ?? notFoundHint
            return errorJSON(code: errorCode, message: message, hint: hint)
        }

        if let left = numericValue(object["left"]),
           let top = numericValue(object["top"]),
           let right = numericValue(object["right"]),
           let bottom = numericValue(object["bottom"]),
           right > left, bottom > top {
            let boxLeft = left.rounded()
            let boxTop = top.rounded()
            let boxRight = right.rounded()
            let boxBottom = bottom.rounded()
            let xPercent = (left + right) / 2.0 / Double(imageWidth) * 100.0
            let yPercent = (top + bottom) / 2.0 / Double(imageHeight) * 100.0
            if (0...100).contains(xPercent), (0...100).contains(yPercent) {
                let boundsExtra: [String: Any] = [
                    "left": boxLeft,
                    "top": boxTop,
                    "right": boxRight,
                    "bottom": boxBottom
                ]
                if isAgreementCheckboxDescription(description),
                   isSmallCheckboxBounds(
                       left: Int(boxLeft),
                       top: Int(boxTop),
                       right: Int(boxRight),
                       bottom: Int(boxBottom),
                       imageWidth: imageWidth
                   ) {
                    Logger.chat(
                        "locate_control: agreement checkbox trust vision bounds "
                        + "[\(Int(boxLeft)),\(Int(boxTop))][\(Int(boxRight)),\(Int(boxBottom))]"
                    )
                    return successJSON(
                        xPercent: roundPercent(xPercent),
                        yPercent: roundPercent(yPercent),
                        source: "vision",
                        imageWidth: imageWidth,
                        imageHeight: imageHeight,
                        extra: boundsExtra.merging([
                            "adjustment": "agreement_checkbox_bounds_trusted"
                        ]) { current, _ in current }
                    )
                }
                let adjusted = adjustVisionPointForDescription(
                    xPercent: xPercent,
                    yPercent: yPercent,
                    description: description,
                    imageData: imageData,
                    imageWidth: imageWidth,
                    imageHeight: imageHeight
                )
                return successJSON(
                    xPercent: roundPercent(adjusted.x),
                    yPercent: roundPercent(adjusted.y),
                    source: "vision",
                    imageWidth: imageWidth,
                    imageHeight: imageHeight,
                    extra: boundsExtra.merging(adjusted.adjustment) { current, _ in current }
                )
            }
        }

        // Compatible: absolute pixels from older prompt replies
        if let x = numericValue(object["x"]),
           let y = numericValue(object["y"]),
           x >= 0, y >= 0,
           x <= Double(imageWidth), y <= Double(imageHeight) {
            let xPercent = x / Double(imageWidth) * 100.0
            let yPercent = y / Double(imageHeight) * 100.0
            let adjusted = adjustVisionPointForDescription(
                xPercent: xPercent,
                yPercent: yPercent,
                description: description,
                imageData: imageData,
                imageWidth: imageWidth,
                imageHeight: imageHeight
            )
            return successJSON(
                xPercent: roundPercent(adjusted.x),
                yPercent: roundPercent(adjusted.y),
                source: "vision",
                imageWidth: imageWidth,
                imageHeight: imageHeight,
                extra: adjusted.adjustment
            )
        }

        return errorJSON(
            code: "not_found",
            message: "模型返回缺少有效的像素坐标或包围盒。",
            hint: notFoundHint
        )
    }

    // MARK: - Grid overlay

    private static func overlayPixelGrid(pngData: Data) -> Data? {
        guard
            let source = CGImageSourceCreateWithData(pngData as CFData, nil),
            let cgImage = CGImageSourceCreateImageAtIndex(source, 0, nil)
        else { return nil }

        let width = cgImage.width
        let height = cgImage.height
        guard width > 0, height > 0 else { return nil }

        guard
            let context = CGContext(
                data: nil,
                width: width,
                height: height,
                bitsPerComponent: 8,
                bytesPerRow: 0,
                space: CGColorSpaceCreateDeviceRGB(),
                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
            )
        else { return nil }

        context.draw(cgImage, in: CGRect(x: 0, y: 0, width: width, height: height))

        context.setStrokeColor(CGColor(red: 0, green: 1, blue: 1, alpha: 0.55))
        context.setLineWidth(max(1.0, Double(min(width, height)) / 400.0))

        for i in 1..<10 {
            let x = CGFloat(width) * CGFloat(i) / 10.0
            context.move(to: CGPoint(x: x, y: 0))
            context.addLine(to: CGPoint(x: x, y: CGFloat(height)))
            let y = CGFloat(height) * CGFloat(i) / 10.0
            context.move(to: CGPoint(x: 0, y: y))
            context.addLine(to: CGPoint(x: CGFloat(width), y: y))
        }
        context.strokePath()

        guard let stamped = context.makeImage() else { return nil }
        let nsImage = NSImage(cgImage: stamped, size: NSSize(width: width, height: height))
        guard
            let tiff = nsImage.tiffRepresentation,
            let rep = NSBitmapImageRep(data: tiff),
            let png = rep.representation(using: .png, properties: [:])
        else { return nil }
        return png
    }

    // MARK: - Helpers

    private static func imagePixelSize(data: Data) -> (Int, Int)? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
        guard
            let props = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
            let width = props[kCGImagePropertyPixelWidth] as? Int,
            let height = props[kCGImagePropertyPixelHeight] as? Int
        else { return nil }
        return (width, height)
    }

    static func extractJSONObject(from text: String) -> [String: Any]? {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if let data = trimmed.data(using: .utf8),
           let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
           isLocateJSONObject(object) {
            return object
        }

        if let start = trimmed.firstIndex(of: "{"),
           let end = trimmed.lastIndex(of: "}"),
           start < end {
            let slice = String(trimmed[start...end])
            if let data = slice.data(using: .utf8),
               let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
               isLocateJSONObject(object) {
                return object
            }
        }

        guard let regex = try? NSRegularExpression(pattern: #"\{[^{}]*\}"#) else { return nil }
        let range = NSRange(trimmed.startIndex..<trimmed.endIndex, in: trimmed)
        let matches = regex.matches(in: trimmed, range: range)
        for match in matches.reversed() {
            guard let objectRange = Range(match.range, in: trimmed) else { continue }
            let slice = String(trimmed[objectRange])
            if let data = slice.data(using: .utf8),
               let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
               isLocateJSONObject(object) {
                return object
            }
        }
        return nil
    }

    private static func isLocateJSONObject(_ object: [String: Any]) -> Bool {
        if object["error"] != nil { return true }
        if numericValue(object["x"]) != nil, numericValue(object["y"]) != nil { return true }
        if numericValue(object["left"]) != nil,
           numericValue(object["top"]) != nil,
           numericValue(object["right"]) != nil,
           numericValue(object["bottom"]) != nil {
            return true
        }
        return false
    }

    private static func isAgreementCheckboxDescription(_ description: String) -> Bool {
        let q = normalize(description)
        let keywords = [
            "协议", "隐私", "已阅读", "阅读并同意", "我已阅读", "同意协议",
            "信息服务协议", "软件使用协议", "privacy", "agreement", "terms"
        ]
        return isCheckboxLikeDescription(description)
            && keywords.contains { q.contains(normalize($0)) }
    }

    private static func isSmallCheckboxBounds(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        imageWidth: Int
    ) -> Bool {
        let width = right - left
        let height = bottom - top
        guard width > 0, height > 0 else { return false }
        return width <= 80
            && height <= 80
            && left < imageWidth / 4
    }

    private static func agreementCheckboxAdjustmentMetadata(
        adjustment: String,
        rawXPercent: Double,
        rawYPercent: Double,
        adjustedXPercent: Double,
        adjustedYPercent: Double,
        confidence: Double = 0.0
    ) -> [String: Any] {
        [
            "adjustment": adjustment,
            "raw_x_percent": roundPercent(rawXPercent),
            "raw_y_percent": roundPercent(rawYPercent),
            "adjusted_x_percent": roundPercent(adjustedXPercent),
            "adjusted_y_percent": roundPercent(adjustedYPercent),
            "confidence": roundPercent(confidence)
        ]
    }

    private static func adjustVisionPointForDescription(
        xPercent: Double,
        yPercent: Double,
        description: String,
        imageData: Data,
        imageWidth: Int,
        imageHeight: Int
    ) -> (x: Double, y: Double, adjustment: [String: Any]) {
        guard isAgreementCheckboxDescription(description) else {
            return (xPercent, yPercent, [:])
        }

        if yPercent >= 70.0, (3.0...15.0).contains(xPercent) {
            Logger.chat(
                "locate_control: agreement checkbox trust vision bottom "
                + "raw=(\(roundPercent(xPercent)),\(roundPercent(yPercent)))"
            )
            return (
                xPercent,
                yPercent,
                agreementCheckboxAdjustmentMetadata(
                    adjustment: "agreement_checkbox_vision_bottom_trusted",
                    rawXPercent: xPercent,
                    rawYPercent: yPercent,
                    adjustedXPercent: xPercent,
                    adjustedYPercent: yPercent
                )
            )
        }

        if let candidate = detectAgreementCheckboxCenter(
            imageData: imageData,
            imageWidth: imageWidth,
            imageHeight: imageHeight,
            hintXPercent: xPercent,
            hintYPercent: yPercent
        ) {
            let xDelta = abs(candidate.x - xPercent)
            let yDelta = abs(candidate.y - yPercent)
            if xDelta <= 8.0, yDelta <= 10.0 {
                Logger.chat(
                    "locate_control: agreement checkbox image candidate "
                    + "raw=(\(roundPercent(xPercent)),\(roundPercent(yPercent))) "
                    + "detected=(\(roundPercent(candidate.x)),\(roundPercent(candidate.y))) "
                    + "confidence=\(roundPercent(candidate.confidence))"
                )
                return (
                    candidate.x,
                    candidate.y,
                    agreementCheckboxAdjustmentMetadata(
                        adjustment: "agreement_checkbox_image_detect",
                        rawXPercent: xPercent,
                        rawYPercent: yPercent,
                        adjustedXPercent: candidate.x,
                        adjustedYPercent: candidate.y,
                        confidence: candidate.confidence
                    )
                )
            }
            Logger.chat(
                "locate_control: agreement checkbox image detection rejected "
                + "raw=(\(roundPercent(xPercent)),\(roundPercent(yPercent))) "
                + "detected=(\(roundPercent(candidate.x)),\(roundPercent(candidate.y))) "
                + "delta=(\(roundPercent(xDelta)),\(roundPercent(yDelta)))"
            )
        }

        let adjustedX: Double
        let adjustedY: Double
        let adjustment: String
        if (40.0...60.0).contains(yPercent) {
            adjustedX = 7.5
            adjustedY = 36.0
            adjustment = "agreement_checkbox_safe_region_fallback"
        } else if (10.0...20.0).contains(xPercent), yPercent < 55.0 {
            adjustedX = max(4.0, xPercent - 5.5)
            adjustedY = min(max(yPercent, 32.0), 38.0)
            adjustment = "agreement_checkbox_left_and_y_clamp"
        } else {
            return (xPercent, yPercent, [:])
        }

        Logger.chat(
            "locate_control: agreement checkbox adjust "
            + "raw=(\(roundPercent(xPercent)),\(roundPercent(yPercent))) "
            + "adjusted=(\(roundPercent(adjustedX)),\(roundPercent(adjustedY))) "
            + "mode=\(adjustment)"
        )
        return (
            adjustedX,
            adjustedY,
            agreementCheckboxAdjustmentMetadata(
                adjustment: adjustment,
                rawXPercent: xPercent,
                rawYPercent: yPercent,
                adjustedXPercent: adjustedX,
                adjustedYPercent: adjustedY
            )
        )
    }

    private struct PixelRegion {
        let xStart: Int
        let xEnd: Int
        let yStart: Int
        let yEnd: Int
    }

    private static func agreementCheckboxSearchRegions(
        imageWidth: Int,
        imageHeight: Int,
        hintXPercent: Double?,
        hintYPercent: Double?
    ) -> [PixelRegion] {
        var regions: [PixelRegion] = []
        if let hintXPercent, let hintYPercent {
            let centerX = Int((hintXPercent / 100.0 * Double(imageWidth)).rounded())
            let centerY = Int((hintYPercent / 100.0 * Double(imageHeight)).rounded())
            let marginX = max(24, Int(Double(imageWidth) * 0.05))
            let marginY = max(48, Int(Double(imageHeight) * 0.06))
            regions.append(
                PixelRegion(
                    xStart: max(0, centerX - marginX),
                    xEnd: min(imageWidth - 1, centerX + marginX),
                    yStart: max(0, centerY - marginY),
                    yEnd: min(imageHeight - 1, centerY + marginY)
                )
            )
        }
        if hintYPercent == nil || hintYPercent! < 55.0 {
            regions.append(
                PixelRegion(
                    xStart: max(0, Int(Double(imageWidth) * 0.03)),
                    xEnd: min(imageWidth - 1, Int(Double(imageWidth) * 0.11)),
                    yStart: max(0, Int(Double(imageHeight) * 0.28)),
                    yEnd: min(imageHeight - 1, Int(Double(imageHeight) * 0.42))
                )
            )
        }
        if hintYPercent == nil || hintYPercent! > 55.0 {
            regions.append(
                PixelRegion(
                    xStart: max(0, Int(Double(imageWidth) * 0.03)),
                    xEnd: min(imageWidth - 1, Int(Double(imageWidth) * 0.11)),
                    yStart: max(0, Int(Double(imageHeight) * 0.78)),
                    yEnd: min(imageHeight - 1, Int(Double(imageHeight) * 0.97))
                )
            )
        }
        return regions
    }

    private static func detectAgreementCheckboxCenter(
        imageData: Data,
        imageWidth: Int,
        imageHeight: Int,
        hintXPercent: Double? = nil,
        hintYPercent: Double? = nil
    ) -> (x: Double, y: Double, confidence: Double)? {
        guard
            let image = NSBitmapImageRep(data: imageData),
            image.pixelsWide == imageWidth,
            image.pixelsHigh == imageHeight
        else {
            Logger.chat("locate_control: agreement checkbox image detection unavailable")
            return nil
        }

        let radius = max(5, Int(Double(imageWidth) * 0.015))
        let sampleOffsets = stride(from: 0, to: 360, by: 30).map { degrees in
            let radians = Double(degrees) * .pi / 180.0
            return (
                dx: Int((cos(radians) * Double(radius)).rounded()),
                dy: Int((sin(radians) * Double(radius)).rounded())
            )
        }

        var best: (x: Int, y: Int, score: Double)?
        for region in agreementCheckboxSearchRegions(
            imageWidth: imageWidth,
            imageHeight: imageHeight,
            hintXPercent: hintXPercent,
            hintYPercent: hintYPercent
        ) {
            for centerY in stride(from: region.yStart, through: region.yEnd, by: 2) {
                for centerX in stride(from: region.xStart, through: region.xEnd, by: 2) {
                    var darkSamples = 0
                    var validSamples = 0
                    for offset in sampleOffsets {
                        let x = centerX + offset.dx
                        let y = centerY + offset.dy
                        guard x >= 0, x < imageWidth, y >= 0, y < imageHeight,
                              let color = image.colorAt(x: x, y: y) else { continue }
                        validSamples += 1
                        let luminance = 0.299 * color.redComponent
                            + 0.587 * color.greenComponent
                            + 0.114 * color.blueComponent
                        if luminance < 0.92 && luminance > 0.35 {
                            darkSamples += 1
                        }
                    }
                    guard validSamples > 0 else { continue }
                    let score = Double(darkSamples) / Double(validSamples)
                    if shouldPreferCheckboxCandidate(
                        current: best,
                        candidate: (centerX, centerY, score),
                        imageWidth: imageWidth,
                        imageHeight: imageHeight,
                        hintXPercent: hintXPercent,
                        hintYPercent: hintYPercent
                    ) {
                        best = (centerX, centerY, score)
                    }
                }
            }
        }

        guard let best, best.score >= 0.35 else {
            Logger.chat(
                "locate_control: agreement checkbox image detection low confidence "
                + "score=\(roundPercent(best?.score ?? 0))"
            )
            return nil
        }
        return (
            Double(best.x) / Double(imageWidth) * 100.0,
            Double(best.y) / Double(imageHeight) * 100.0,
            best.score
        )
    }

    private static func shouldPreferCheckboxCandidate(
        current: (x: Int, y: Int, score: Double)?,
        candidate: (x: Int, y: Int, score: Double),
        imageWidth: Int,
        imageHeight: Int,
        hintXPercent: Double?,
        hintYPercent: Double?
    ) -> Bool {
        guard let current else { return true }
        if candidate.score > current.score + 0.08 { return true }
        if current.score > candidate.score + 0.08 { return false }
        guard let hintXPercent, let hintYPercent else {
            return candidate.score >= current.score
        }
        let hintX = hintXPercent / 100.0 * Double(imageWidth)
        let hintY = hintYPercent / 100.0 * Double(imageHeight)
        let currentDistance = hypot(Double(current.x) - hintX, Double(current.y) - hintY)
        let candidateDistance = hypot(Double(candidate.x) - hintX, Double(candidate.y) - hintY)
        if candidateDistance + 12.0 < currentDistance { return true }
        if currentDistance + 12.0 < candidateDistance { return false }
        return candidate.score >= current.score
    }

    private static func numericValue(_ value: Any?) -> Double? {
        if let number = value as? Double { return number }
        if let number = value as? Int { return Double(number) }
        if let text = value as? String, let number = Double(text) { return number }
        return nil
    }

    private static func roundPercent(_ value: Double) -> Double {
        (value * 100).rounded() / 100
    }

    private static func successJSON(
        xPercent: Double,
        yPercent: Double,
        source: String,
        imageWidth: Int?,
        imageHeight: Int?,
        extra: [String: Any] = [:]
    ) -> String {
        let x = imageWidth.map { (xPercent / 100.0 * Double($0)).rounded() } ?? xPercent
        let y = imageHeight.map { (yPercent / 100.0 * Double($0)).rounded() } ?? yPercent
        var payload: [String: Any] = [
            "x": x,
            "y": y,
            "source": source
        ]
        if let imageWidth { payload["image_width"] = imageWidth }
        if let imageHeight { payload["image_height"] = imageHeight }
        for (key, value) in extra {
            payload[key] = value
        }
        return jsonString(payload)
    }

    private static func errorJSON(code: String, message: String, hint: String? = nil) -> String {
        var payload: [String: Any] = [
            "error": code,
            "message": message
        ]
        if let hint {
            payload["hint"] = hint
        } else if code == "not_found" {
            payload["hint"] = notFoundHint
        }
        return jsonString(payload)
    }

    private static func jsonString(_ object: [String: Any]) -> String {
        guard
            let data = try? JSONSerialization.data(withJSONObject: object, options: [.sortedKeys]),
            let text = String(data: data, encoding: .utf8)
        else {
            return #"{"error":"model_error","message":"JSON 序列化失败"}"#
        }
        return text
    }
}
