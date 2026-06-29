import UIKit
import VisionKit
import Vision

/**
 Scanner de documento via VisionKit, focado em ler o código de barras ITF no canto
 superior direito (fluxo Concluir Guias). Retorna [{ uri, barcode, success }].
 */
@available(iOS 13.0, *)
public class DocScanner: NSObject, VNDocumentCameraViewControllerDelegate {

    private var viewController: UIViewController?
    private var successHandler: ([[String: Any]]) -> Void
    private var errorHandler: (String) -> Void
    private var cancelHandler: () -> Void
    private var responseType: String
    private var croppedImageQuality: Int

    public init(
        _ viewController: UIViewController? = nil,
        successHandler: @escaping ([[String: Any]]) -> Void = { _ in },
        errorHandler: @escaping (String) -> Void = { _ in },
        cancelHandler: @escaping () -> Void = {},
        responseType: String = ResponseType.imageFilePath,
        croppedImageQuality: Int = 100
    ) {
        self.viewController = viewController
        self.successHandler = successHandler
        self.errorHandler = errorHandler
        self.cancelHandler = cancelHandler
        self.responseType = responseType
        self.croppedImageQuality = croppedImageQuality
    }

    public convenience override init() {
        self.init(nil)
    }

    public func startScan(
        _ viewController: UIViewController? = nil,
        successHandler: @escaping ([[String: Any]]) -> Void = { _ in },
        errorHandler: @escaping (String) -> Void = { _ in },
        cancelHandler: @escaping () -> Void = {},
        responseType: String? = ResponseType.imageFilePath,
        croppedImageQuality: Int? = 100
    ) {
        self.viewController = viewController
        self.successHandler = successHandler
        self.errorHandler = errorHandler
        self.cancelHandler = cancelHandler
        self.responseType = responseType ?? ResponseType.imageFilePath
        self.croppedImageQuality = croppedImageQuality ?? 100

        self.startScan()
    }

    public func startScan() {
        DispatchQueue.main.async {
            guard VNDocumentCameraViewController.isSupported else {
                self.errorHandler("Document scanning is not supported on this device")
                return
            }
            let documentCameraViewController = VNDocumentCameraViewController()
            documentCameraViewController.delegate = self
            self.viewController?.present(documentCameraViewController, animated: true)
        }
    }

    public func documentCameraViewController(
        _ controller: VNDocumentCameraViewController,
        didFinishWith scan: VNDocumentCameraScan
    ) {
        var processedResults: [[String: Any]] = []

        for pageNumber in 0...scan.pageCount - 1 {
            var scannedImage: UIImage = scan.imageOfPage(at: pageNumber)
            var barcodeValue = scannedImage.findITFBarcodeInTopRightAreaSync()

            if barcodeValue == nil {
                if let rotated = scannedImage.rotate(radians: .pi / 2),
                   let found = rotated.findITFBarcodeInTopRightAreaSync() {
                    barcodeValue = found
                    scannedImage = rotated
                }
            }
            if barcodeValue == nil {
                if let rotated = scannedImage.rotate(radians: -.pi / 2),
                   let found = rotated.findITFBarcodeInTopRightAreaSync() {
                    barcodeValue = found
                    scannedImage = rotated
                }
            }
            if barcodeValue == nil {
                if let rotated = scannedImage.rotate(radians: .pi),
                   let found = rotated.findITFBarcodeInTopRightAreaSync() {
                    barcodeValue = found
                    scannedImage = rotated
                }
            }

            guard let imageData = scannedImage.jpegData(compressionQuality: CGFloat(croppedImageQuality) / 100.0) else {
                goBackToPreviousView(controller)
                errorHandler("Unable to get scanned document in jpeg format")
                return
            }

            var documentIdentifier = ""
            switch responseType {
            case ResponseType.base64:
                documentIdentifier = imageData.base64EncodedString()
            case ResponseType.imageFilePath:
                do {
                    let path = FileUtil().createImageFile(pageNumber)
                    try imageData.write(to: path)
                    documentIdentifier = path.absoluteString
                } catch {
                    goBackToPreviousView(controller)
                    errorHandler("Unable to save scanned image: \(error.localizedDescription)")
                    return
                }
            default:
                goBackToPreviousView(controller)
                errorHandler("responseType must be base64 or imageFilePath")
                return
            }

            processedResults.append([
                "uri": documentIdentifier,
                "barcode": barcodeValue as Any,
                "success": barcodeValue != nil
            ])
        }

        goBackToPreviousView(controller)
        self.successHandler(processedResults)
    }

    public func documentCameraViewControllerDidCancel(_ controller: VNDocumentCameraViewController) {
        goBackToPreviousView(controller)
        cancelHandler()
    }

    public func documentCameraViewController(
        _ controller: VNDocumentCameraViewController,
        didFailWithError error: Error
    ) {
        goBackToPreviousView(controller)
        errorHandler(error.localizedDescription)
    }

    private func goBackToPreviousView(_ controller: VNDocumentCameraViewController) {
        DispatchQueue.main.async {
            controller.dismiss(animated: true)
        }
    }
}

extension UIImage {
    func rotate(radians: Float) -> UIImage? {
        var newSize = CGRect(origin: .zero, size: size)
            .applying(CGAffineTransform(rotationAngle: CGFloat(radians)))
            .integral.size
        newSize.width = floor(newSize.width)
        newSize.height = floor(newSize.height)

        UIGraphicsBeginImageContextWithOptions(newSize, false, scale)
        let context = UIGraphicsGetCurrentContext()!
        context.translateBy(x: newSize.width / 2, y: newSize.height / 2)
        context.rotate(by: CGFloat(radians))
        draw(in: CGRect(x: -size.width / 2, y: -size.height / 2, width: size.width, height: size.height))
        let result = UIGraphicsGetImageFromCurrentImageContext()
        UIGraphicsEndImageContext()
        return result
    }
}
