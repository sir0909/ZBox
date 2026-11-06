import Foundation

/// Offline decoder for a *single* GetDeviceInfo transaction. The ImageCaptureCore
/// callback's actual byte layout/transaction behaviour is not yet verified on iOS.
public enum USBDeviceInfoCodec {
    public static let maxDatasetBytes = 256 * 1024
    public enum DataFormat: Sendable { case bareDataset, ptpDataContainer }
    public enum Failure: Error, Equatable, Sendable {
        case responseMalformed, wrongTransaction, cameraResponse(UInt16)
        case dataTooLarge, invalidDataContainer, invalidDataset
    }

    /// The caller must supply the response and data separately and explicitly specify
    /// the data representation; guessing from arbitrary dataset bytes is unsafe.
    public static func decode(response: Data, data: Data, format: DataFormat,
                              transactionID: UInt32) throws -> PtpDeviceInfo {
        guard response.count >= PtpContainer.headerSize,
              response.count <= PtpContainer.headerSize + 5 * 4,
              let header = try? PtpContainer.parseHeader(response),
              header.type == .response,
              Int(header.length) == response.count,
              (response.count - PtpContainer.headerSize).isMultiple(of: 4) else {
            throw Failure.responseMalformed
        }
        guard header.transactionId == transactionID else { throw Failure.wrongTransaction }
        guard header.code == PtpResponseCode.ok else { throw Failure.cameraResponse(header.code) }
        let dataset: Data
        switch format {
        case .bareDataset:
            guard data.count <= maxDatasetBytes else { throw Failure.dataTooLarge }
            dataset = data
        case .ptpDataContainer:
            guard data.count <= maxDatasetBytes + PtpContainer.headerSize else { throw Failure.dataTooLarge }
            guard let dataHeader = try? PtpContainer.parseHeader(data), dataHeader.type == .data,
                  dataHeader.code == PtpOpCode.getDeviceInfo,
                  dataHeader.transactionId == transactionID,
                  Int(dataHeader.length) == data.count else { throw Failure.invalidDataContainer }
            dataset = data.slice(at: PtpContainer.headerSize, count: data.count - PtpContainer.headerSize)
        }
        do { return try PtpDeviceInfo.parse(dataset) }
        catch { throw Failure.invalidDataset }
    }
}
