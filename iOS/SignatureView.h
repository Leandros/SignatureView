#import <UIKit/UIKit.h>
#import <GLKit/GLKit.h>

@protocol SignatureViewDelegate;
@interface SignatureView : GLKView

@property (assign, nonatomic, readonly) BOOL hasSignature;
@property (strong, nonatomic, readonly) UIImage *signatureImage;
@property (assign, nonatomic) id<SignatureViewDelegate> signatureDelegate;

- (void)erase;

@end


@protocol SignatureViewDelegate <NSObject>

@optional
- (void)signatureViewDidStartDrawing:(SignatureView *)signatureView;

@end
