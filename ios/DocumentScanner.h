#if RCT_NEW_ARCH_ENABLED
#import <DocumentScannerSpec/DocumentScannerSpec.h>
@interface DocumentScanner : NSObject <NativeDocumentScannerSpec>
#else
#import <React/RCTBridgeModule.h>
@interface DocumentScanner : NSObject <RCTBridgeModule>
#endif

@end
