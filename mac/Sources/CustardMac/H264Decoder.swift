import CoreMedia
import CoreVideo
import Foundation
import VideoToolbox

final class H264Decoder {
    var onFrame: ((CGImage) -> Void)?

    private let queue = DispatchQueue(label: "com.kymjs.custard.h264.decode", qos: .userInteractive)
    private var session: VTDecompressionSession?
    private var formatDescription: CMFormatDescription?
    private var loggedWaitingForKeyframe = false
    private var receivedFrameCount = 0
    private var awaitingIdr = true
    private var pendingFrame: (data: Data, isKeyFrame: Bool)?
    private var isDecoding = false
    private var submittedGeneration: UInt64 = 0
    private var decodingGeneration: UInt64 = 0

    func reset() {
        queue.async {
            self.session = nil
            self.formatDescription = nil
            self.loggedWaitingForKeyframe = false
            self.receivedFrameCount = 0
            self.awaitingIdr = true
            self.pendingFrame = nil
            self.isDecoding = false
            self.submittedGeneration = 0
            self.decodingGeneration = 0
        }
    }

    func decode(_ data: Data, isKeyFrame: Bool) {
        queue.async {
            self.submittedGeneration &+= 1
            self.pendingFrame = (data, isKeyFrame)
            self.processPendingFrames()
        }
    }

    private func processPendingFrames() {
        guard !isDecoding, let frame = pendingFrame else { return }
        pendingFrame = nil
        isDecoding = true
        decodingGeneration = submittedGeneration
        decodeFrame(frame.data, isKeyFrame: frame.isKeyFrame)
    }

    private func decodeFrame(_ data: Data, isKeyFrame: Bool) {
        receivedFrameCount += 1
        if receivedFrameCount <= 3 || receivedFrameCount % 120 == 0 {
            Logger.info("video frame #\(receivedFrameCount) size=\(data.count) key=\(isKeyFrame)")
        }

        let effectiveKeyFrame = isKeyFrame || Self.containsIdrNal(data)

        if formatDescription == nil {
            guard let description = Self.createFormatDescription(from: data) else {
                if !loggedWaitingForKeyframe || effectiveKeyFrame {
                    loggedWaitingForKeyframe = true
                    let preview = data.prefix(8).map { String(format: "%02x", $0) }.joined(separator: " ")
                    let nalTypes = Self.parseNalUnits(data).map { Int($0.type) }
                    Logger.warn(
                        "waiting for SPS/PPS " +
                            "(size=\(data.count), key=\(isKeyFrame), idr=\(Self.containsIdrNal(data)), " +
                            "nals=\(nalTypes), head=\(preview))"
                    )
                }
                finishDecodeCycle()
                return
            }
            loggedWaitingForKeyframe = false
            formatDescription = description
            createSession(format: description)
            Logger.info("H.264 format ready (key=\(isKeyFrame), idr=\(Self.containsIdrNal(data)))")
        }

        guard let videoFormat = self.formatDescription, let session = self.session else {
            finishDecodeCycle()
            return
        }

        let decodeData = Self.avccData(from: data)

        guard Self.containsSliceNal(decodeData) else {
            if effectiveKeyFrame, let newDescription = Self.createFormatDescription(from: data) {
                formatDescription = newDescription
                createSession(format: newDescription)
            } else if formatDescription == nil, let newDescription = Self.createFormatDescription(from: data) {
                formatDescription = newDescription
                createSession(format: newDescription)
                Logger.info("H.264 format ready from config-only packet")
            }
            finishDecodeCycle()
            return
        }

        if awaitingIdr, !Self.containsIdrNal(data) {
            finishDecodeCycle()
            return
        }

        var blockBuffer: CMBlockBuffer?
        let blockStatus = decodeData.withUnsafeBytes { rawBuffer -> OSStatus in
            CMBlockBufferCreateWithMemoryBlock(
                allocator: kCFAllocatorDefault,
                memoryBlock: nil,
                blockLength: decodeData.count,
                blockAllocator: kCFAllocatorDefault,
                customBlockSource: nil,
                offsetToData: 0,
                dataLength: decodeData.count,
                flags: 0,
                blockBufferOut: &blockBuffer
            )
        }
        guard blockStatus == kCMBlockBufferNoErr, let blockBuffer else {
            finishDecodeCycle()
            return
        }

        let replaceStatus = decodeData.withUnsafeBytes { rawBuffer -> OSStatus in
            CMBlockBufferReplaceDataBytes(
                with: rawBuffer.baseAddress!,
                blockBuffer: blockBuffer,
                offsetIntoDestination: 0,
                dataLength: decodeData.count
            )
        }
        guard replaceStatus == kCMBlockBufferNoErr else {
            finishDecodeCycle()
            return
        }

        var sampleBuffer: CMSampleBuffer?
        var sampleSize = decodeData.count
        let timing = CMSampleTimingInfo(
            duration: .invalid,
            presentationTimeStamp: .invalid,
            decodeTimeStamp: .invalid
        )
        let sampleStatus = CMSampleBufferCreateReady(
            allocator: kCFAllocatorDefault,
            dataBuffer: blockBuffer,
            formatDescription: videoFormat,
            sampleCount: 1,
            sampleTimingEntryCount: 1,
            sampleTimingArray: [timing],
            sampleSizeEntryCount: 1,
            sampleSizeArray: &sampleSize,
            sampleBufferOut: &sampleBuffer
        )
        guard sampleStatus == noErr, let sampleBuffer else {
            finishDecodeCycle()
            return
        }

        var infoFlags = VTDecodeInfoFlags()
        let decodeStatus = VTDecompressionSessionDecodeFrame(
            session,
            sampleBuffer: sampleBuffer,
            flags: [],
            frameRefcon: nil,
            infoFlagsOut: &infoFlags
        )
        if decodeStatus != noErr {
            if effectiveKeyFrame {
                Logger.warn("decode failed status=\(decodeStatus), retrying format")
                if let newDescription = Self.createFormatDescription(from: data) {
                    formatDescription = newDescription
                    createSession(format: newDescription)
                }
            }
            finishDecodeCycle()
        } else if Self.containsIdrNal(data) {
            awaitingIdr = false
        }
    }

    private func finishDecodeCycle() {
        isDecoding = false
        processPendingFrames()
    }

    private func createSession(format: CMFormatDescription) {
        session = nil
        var callback = VTDecompressionOutputCallbackRecord(
            decompressionOutputCallback: Self.decompressionOutputCallback,
            decompressionOutputRefCon: Unmanaged.passUnretained(self).toOpaque()
        )
        var newSession: VTDecompressionSession?
        let status = VTDecompressionSessionCreate(
            allocator: kCFAllocatorDefault,
            formatDescription: format,
            decoderSpecification: nil,
            imageBufferAttributes: [
                kCVPixelBufferPixelFormatTypeKey: kCVPixelFormatType_32BGRA,
                kCVPixelBufferIOSurfacePropertiesKey: [:] as CFDictionary
            ] as CFDictionary,
            outputCallback: &callback,
            decompressionSessionOut: &newSession
        )
        guard status == noErr, let newSession else { return }
        VTSessionSetProperty(
            newSession,
            key: kVTDecompressionPropertyKey_RealTime,
            value: kCFBooleanTrue!
        )
        session = newSession
    }

    private static let decompressionOutputCallback: VTDecompressionOutputCallback = { refCon, _, status, _, imageBuffer, _, _ in
        guard let refCon else { return }
        let decoder = Unmanaged<H264Decoder>.fromOpaque(refCon).takeUnretainedValue()

        guard status == noErr,
              let imageBuffer,
              let cgImage = cgImage(from: imageBuffer) else {
            decoder.queue.async { decoder.finishDecodeCycle() }
            return
        }

        decoder.queue.async {
            decoder.awaitingIdr = false
            defer { decoder.finishDecodeCycle() }
            guard decoder.decodingGeneration == decoder.submittedGeneration else { return }
            DispatchQueue.main.async {
                decoder.onFrame?(cgImage)
            }
        }
    }

    private static func cgImage(from pixelBuffer: CVPixelBuffer) -> CGImage? {
        CVPixelBufferLockBaseAddress(pixelBuffer, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(pixelBuffer, .readOnly) }

        guard let baseAddress = CVPixelBufferGetBaseAddress(pixelBuffer) else { return nil }
        let width = CVPixelBufferGetWidth(pixelBuffer)
        let height = CVPixelBufferGetHeight(pixelBuffer)
        let bytesPerRow = CVPixelBufferGetBytesPerRow(pixelBuffer)

        guard let context = CGContext(
            data: baseAddress,
            width: width,
            height: height,
            bitsPerComponent: 8,
            bytesPerRow: bytesPerRow,
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue
        ) else { return nil }

        return context.makeImage()
    }

    private static func createFormatDescription(from data: Data) -> CMFormatDescription? {
        let nalUnits = parseNalUnits(data)
        guard let sps = nalUnits.first(where: { $0.type == 7 })?.payload,
              let pps = nalUnits.first(where: { $0.type == 8 })?.payload else {
            return nil
        }

        var status: OSStatus = -1
        var format: CMFormatDescription?
        sps.withUnsafeBytes { spsRaw in
            pps.withUnsafeBytes { ppsRaw in
                guard let spsPtr = spsRaw.baseAddress?.assumingMemoryBound(to: UInt8.self),
                      let ppsPtr = ppsRaw.baseAddress?.assumingMemoryBound(to: UInt8.self) else {
                    return
                }
                let pointers = [spsPtr, ppsPtr]
                var sizes = [sps.count, pps.count]
                status = pointers.withUnsafeBufferPointer { pointerBuffer in
                    sizes.withUnsafeMutableBufferPointer { sizeBuffer in
                        CMVideoFormatDescriptionCreateFromH264ParameterSets(
                            allocator: kCFAllocatorDefault,
                            parameterSetCount: 2,
                            parameterSetPointers: pointerBuffer.baseAddress!,
                            parameterSetSizes: sizeBuffer.baseAddress!,
                            nalUnitHeaderLength: 4,
                            formatDescriptionOut: &format
                        )
                    }
                }
            }
        }
        return status == noErr ? format : nil
    }

    private static func containsSliceNal(_ data: Data) -> Bool {
        parseNalUnits(data).contains { unit in
            unit.type == 1 || unit.type == 5
        }
    }

    private static func containsIdrNal(_ data: Data) -> Bool {
        parseNalUnits(data).contains { $0.type == 5 }
    }

    private static func avccData(from data: Data) -> Data {
        if looksLikeAnnexB(data) {
            return avccData(fromAnnexB: data)
        }
        if looksLikeAvcc(data) {
            return data
        }
        return avccData(fromAnnexB: data)
    }

    private static func avccData(fromAnnexB data: Data) -> Data {
        let units = parseAnnexB(data)
        var result = Data()
        for unit in units {
            var length = UInt32(unit.payload.count).bigEndian
            withUnsafeBytes(of: &length) { result.append(contentsOf: $0) }
            result.append(unit.payload)
        }
        return result
    }

    private static func parseNalUnits(_ data: Data) -> [(type: UInt8, payload: Data)] {
        // Annex-B start codes (00 00 00 01) must be checked first; otherwise the prefix
        // is misread as AVCC length=1 and SPS/PPS are never found.
        if looksLikeAnnexB(data) {
            return parseAnnexB(data)
        }
        if looksLikeAvcc(data) {
            return parseAvcc(data)
        }
        return parseAnnexB(data)
    }

    private static func looksLikeAvcc(_ data: Data) -> Bool {
        if looksLikeAnnexB(data) {
            return false
        }
        var offset = 0
        var validNals = 0
        while offset + 4 < data.count, validNals < 4 {
            let length: Int = data.subdata(in: offset..<(offset + 4)).withUnsafeBytes {
                Int($0.load(as: UInt32.self).bigEndian)
            }
            guard length > 0, offset + 4 + length <= data.count else { break }
            let type = data[offset + 4] & 0x1F
            guard (1...23).contains(type) else { break }
            validNals += 1
            offset += 4 + length
        }
        return validNals > 0
    }

    private static func looksLikeAnnexB(_ data: Data) -> Bool {
        if data.starts(with: [0, 0, 0, 1]) {
            return data.count > 4 && (1...23).contains(data[4] & 0x1F)
        }
        if data.count > 3, data[0] == 0, data[1] == 0, data[2] == 1 {
            return (1...23).contains(data[3] & 0x1F)
        }
        return false
    }

    private static func parseAvcc(_ data: Data) -> [(type: UInt8, payload: Data)] {
        var units: [(UInt8, Data)] = []
        var offset = 0
        while offset + 4 <= data.count {
            let length: Int = data.subdata(in: offset..<(offset + 4)).withUnsafeBytes {
                Int($0.load(as: UInt32.self).bigEndian)
            }
            offset += 4
            guard length > 0, offset + length <= data.count else { break }
            let nal = data.subdata(in: offset..<(offset + length))
            let type = nal[0] & 0x1F
            units.append((type, nal))
            offset += length
        }
        return units
    }

    private static func parseAnnexB(_ data: Data) -> [(type: UInt8, payload: Data)] {
        var units: [(UInt8, Data)] = []
        let bytes = [UInt8](data)
        var start = 0
        var i = 0

        func startCodeLength(at index: Int) -> Int? {
            if index + 3 < bytes.count, bytes[index] == 0, bytes[index + 1] == 0 {
                if bytes[index + 2] == 1 { return 3 }
                if index + 4 < bytes.count, bytes[index + 2] == 0, bytes[index + 3] == 1 { return 4 }
            }
            return nil
        }

        while i < bytes.count {
            if let length = startCodeLength(at: i) {
                if i > start {
                    let nal = Data(bytes[start..<i])
                    if !nal.isEmpty {
                        units.append((nal[0] & 0x1F, nal))
                    }
                }
                i += length
                start = i
            } else {
                i += 1
            }
        }
        if start < bytes.count {
            let nal = Data(bytes[start..<bytes.count])
            if !nal.isEmpty {
                units.append((nal[0] & 0x1F, nal))
            }
        }
        return units
    }
}
