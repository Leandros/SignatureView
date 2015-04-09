#import "SignatureView.h"
#import <OpenGLES/ES2/glext.h>

#define     STROKE_WIDTH_MIN                    0.006f
#define     STROKE_WIDTH_MAX                    0.040f
#define     STROKE_WIDTH_SMOOTHING              0.500f

#define     VELOCITY_CLAMP_MIN                  20
#define     VELOCITY_CLAMP_MAX                  5000

#define     QUADRATIC_DISTANCE_TOLERANCE        3.0f

#define     MAXIMUM_VERTICES                    100000

#define     clamp(x, y, z)                      (fmaxf(x, fminf(y, z)))
#define     SignaturePointMake(x, y, z)         (SignaturePoint) { x, y, z }
#define     SignaturePointToGPUVertex(x)        (GPUVertex) { x, StrokeColor }

static GLKVector3 StrokeColor = { 0.0f, 0.0f, 0.0f };
typedef struct GPUVertex {
    GLKVector3 position;
    GLKVector3 color;
} GPUVertex;
typedef GLKVector3 SignaturePoint;

static inline void addVertex(uint *length, SignaturePoint v) {
    if ((*length) >= MAXIMUM_VERTICES) {
        return;
    }

    GPUVertex vertex = SignaturePointToGPUVertex(v);
    GLvoid *data = glMapBufferOES(GL_ARRAY_BUFFER, GL_WRITE_ONLY_OES);
    memcpy(data + (sizeof(GPUVertex) * (*length)), &vertex, sizeof(GPUVertex));
    // you want funny results? this is how you get funny results:
    // memcpy(data + (sizeof(SignaturePoint) * (*length)), &v, sizeof(SignaturePoint));
    // memcpy(data + ((sizeof(SignaturePoint) + sizeof(GLKVector3)) * (*length)), &StrokeColor, sizeof(GLKVector3));
    glUnmapBufferOES(GL_ARRAY_BUFFER);

    (*length)++;
}

static inline CGPoint quadraticPointInCurve(CGPoint start, CGPoint end, CGPoint controlPoint, float percent) {
    double a = pow((1.0 - percent), 2.0);
    double b = 2.0 * percent * (1.0 - percent);
    double c = pow(percent, 2.0);

    return CGPointMake((CGFloat) (a * start.x + b * controlPoint.x + c * end.x),
            (CGFloat) (a * start.y + b * controlPoint.y + c * end.y));
}

static inline float generateRandom(float from, float to) {
    return (float) (random() % 10000 / 10000.0 * (to - from) + from);
}

static SignaturePoint perpendicular(SignaturePoint p1, SignaturePoint p2) {
    SignaturePoint retVal;
    retVal.x = p2.y - p1.y;
    retVal.y = -1.0f * (p2.x - p1.x);
    retVal.z = 0.0f;

    return retVal;
}

static SignaturePoint viewPointToGL(CGPoint viewPoint, CGRect bounds) {
    SignaturePoint retVal;
    retVal.x = ((viewPoint.x / bounds.size.width) * 2.0f) - 1.0f;
    retVal.y = ((viewPoint.y / bounds.size.height) * 2.0f - 1.0f) * -1.0f;
    retVal.z = 0.0f;

    return retVal;
}


@interface SignatureView () {
    EAGLContext *context;
    GLKBaseEffect *effect;

    GLuint lineVAO;
    GLuint lineVBO;
    GLuint dotVAO;
    GLuint dotVBO;

    GPUVertex lineVertices[MAXIMUM_VERTICES];
    uint32_t lineLength;

    GPUVertex dotVertices[MAXIMUM_VERTICES];
    uint32_t dotLength;

    // Width of line at current and previous vertex
    float penThickness;
    float previousThickness;

    // Previous points for quadratic bezier computations
    CGPoint previousPoint;
    CGPoint previousMidPoint;
    SignaturePoint previousVertex;
}

#pragma mark - Properties -
@property (assign, nonatomic) BOOL hasSignature;

#pragma mark - Private -
- (void)tearDownGL;
- (void)setupGL;

@end


@implementation SignatureView


- (void)commonInit {
    context = [[EAGLContext alloc] initWithAPI:kEAGLRenderingAPIOpenGLES2];

    if (context) {
        time(NULL);

        self.context = context;
        self.drawableDepthFormat = GLKViewDrawableDepthFormat24;
        self.enableSetNeedsDisplay = YES;

        [self setupGL];

        // Capture touches
        UIPanGestureRecognizer *pan = [[UIPanGestureRecognizer alloc] initWithTarget:self action:@selector(pan:)];
        pan.maximumNumberOfTouches = pan.minimumNumberOfTouches = 1;
        [self addGestureRecognizer:pan];

        // For dotting your i's
        UITapGestureRecognizer *tap = [[UITapGestureRecognizer alloc] initWithTarget:self action:@selector(tap:)];
        [self addGestureRecognizer:tap];

        // Erase with long press
        [self addGestureRecognizer:[[UILongPressGestureRecognizer alloc] initWithTarget:self action:@selector(longPress:)]];

    } else {
        [NSException raise:@"NSOpenGLES2ContextException" format:@"Failed to create OpenGL ES2 context"];
    }
}


- (id)initWithCoder:(NSCoder *)aDecoder {
    if (self = [super initWithCoder:aDecoder]) {
        [self commonInit];
    }
    return self;
}


- (id)initWithFrame:(CGRect)frame context:(EAGLContext *)ctx {
    if (self = [super initWithFrame:frame context:ctx]) {
        [self commonInit];
    }
    return self;
}


- (void)dealloc {
    [self tearDownGL];

    if ([EAGLContext currentContext] == context) {
        [EAGLContext setCurrentContext:nil];
    }
    context = nil;
}

- (void)setupGL {
    [EAGLContext setCurrentContext:context];

    effect = [[GLKBaseEffect alloc] init];
    glDisable(GL_DEPTH_TEST);

    // Signature Lines
    glGenVertexArraysOES(1, &lineVAO);
    glBindVertexArrayOES(lineVAO);

    glGenBuffers(1, &lineVBO);
    glBindBuffer(GL_ARRAY_BUFFER, lineVBO);
    glBufferData(GL_ARRAY_BUFFER, sizeof(lineVertices), lineVertices, GL_DYNAMIC_DRAW);
    [self bindShaderAttributes];

    // Signature Dots
    glGenVertexArraysOES(1, &dotVAO);
    glBindVertexArrayOES(dotVAO);

    glGenBuffers(1, &dotVBO);
    glBindBuffer(GL_ARRAY_BUFFER, dotVBO);
    glBufferData(GL_ARRAY_BUFFER, sizeof(dotVertices), dotVertices, GL_DYNAMIC_DRAW);
    [self bindShaderAttributes];

    glBindVertexArrayOES(0);


    // Perspective
    GLKMatrix4 ortho = GLKMatrix4MakeOrtho(-1.0f, 1.0f, -1.0f, 1.0f, 0.1f, 2.0f);
    effect.transform.projectionMatrix = ortho;

    GLKMatrix4 modelViewMatrix = GLKMatrix4MakeTranslation(0.0f, 0.0f, -1.0f);
    effect.transform.modelviewMatrix = modelViewMatrix;

    lineLength = 0;
    dotLength = 0;
    penThickness = 0.003f;
    previousPoint = CGPointMake(-100.0f, -100.0f);
}

- (void)tearDownGL {
    [EAGLContext setCurrentContext:context];

    glDeleteBuffers(1, &lineVBO);
    glDeleteVertexArraysOES(1, &lineVAO);

    glDeleteBuffers(1, &dotVBO);
    glDeleteVertexArraysOES(1, &dotVAO);

    effect = nil;
}


#pragma mark - Render Loop -
- (void)drawRect:(CGRect)rect {
    glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
    glClear(GL_COLOR_BUFFER_BIT);

    [effect prepareToDraw];

    // Drawing of signature lines
    if (lineLength > 2) {
        glBindVertexArrayOES(lineVAO);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, lineLength);
    }

    if (dotLength > 0) {
        glBindVertexArrayOES(dotVAO);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, dotLength);
    }

}

#pragma mark - Public -
- (void)erase {
    lineLength = 0;
    dotLength = 0;
    self.hasSignature = NO;

    [self setNeedsDisplay];
}

- (UIImage *)signatureImage {
    if (!self.hasSignature) {
        return nil;
    }

    return [self snapshot];
}


#pragma mark - Gesture Recognizers -
- (void)tap:(UITapGestureRecognizer *)t {
    if (self.signatureDelegate) {
        if ([self.signatureDelegate respondsToSelector:@selector(signatureViewDidStartDrawing:)]) {
            [self.signatureDelegate signatureViewDidStartDrawing:self];
        }
    }

    CGPoint l = [t locationInView:self];

    [self drawTap:t.state location:l];
}

- (void)drawTap:(UIGestureRecognizerState)state location:(CGPoint)l {
    if (state == UIGestureRecognizerStateRecognized) {
        glBindBuffer(GL_ARRAY_BUFFER, dotVBO);

        SignaturePoint touchPoint = viewPointToGL(l, self.bounds);
        addVertex(&dotLength, touchPoint);
        addVertex(&dotLength, touchPoint);

        GLKVector2 radius = (GLKVector2) {penThickness * 2.0f * generateRandom(0.5f, 1.5f), penThickness * 2.0f * generateRandom(0.5f, 1.5f)};
        float angle = 0;

        for (int i = 0; i <= 20; i++) {
            SignaturePoint p = touchPoint;
            p.x += radius.x * cosf(angle);
            p.y += radius.y * sinf(angle);

            addVertex(&dotLength, p);
            addVertex(&dotLength, touchPoint);

            angle += M_PI * 2.0 / 20;
        }

        addVertex(&dotLength, touchPoint);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }

    [self setNeedsDisplay];
}


- (void)longPress:(UILongPressGestureRecognizer *)lp {
    [self erase];
}

- (void)pan:(UIPanGestureRecognizer *)p {
    if (self.signatureDelegate) {
        if ([self.signatureDelegate respondsToSelector:@selector(signatureViewDidStartDrawing:)]) {
            [self.signatureDelegate signatureViewDidStartDrawing:self];
        }
    }

    CGPoint v = [p velocityInView:self];
    CGPoint l = [p locationInView:self];

    [self drawPan:p.state location:l velocity:v];
}

- (void)drawPan:(UIGestureRecognizerState)state location:(CGPoint)l velocity:(CGPoint)v {
    glBindBuffer(GL_ARRAY_BUFFER, lineVBO);

    float distance = 0.0f;
    if (previousPoint.x > 0) {
        distance = sqrtf((l.x - previousPoint.x) * (l.x - previousPoint.x) + (l.y - previousPoint.y) * (l.y - previousPoint.y));
    }

    float velocityMagnitude = sqrtf(v.x * v.x + v.y * v.y);
    float clampedVelocityMagnitude = clamp(VELOCITY_CLAMP_MIN, VELOCITY_CLAMP_MAX, velocityMagnitude);
    float normalizedVelocity = (clampedVelocityMagnitude - VELOCITY_CLAMP_MIN) / (VELOCITY_CLAMP_MAX - VELOCITY_CLAMP_MIN);

    float lowPassFilterAlpha = STROKE_WIDTH_SMOOTHING;
    float newThickness = (float) ((STROKE_WIDTH_MAX - STROKE_WIDTH_MIN) * normalizedVelocity + STROKE_WIDTH_MIN);
    penThickness = penThickness * lowPassFilterAlpha + newThickness * (1 - lowPassFilterAlpha);

    if (state == UIGestureRecognizerStateBegan) {
        previousPoint = l;
        previousMidPoint = l;

        previousVertex = viewPointToGL(l, self.bounds);
        previousThickness = penThickness;

        addVertex(&lineLength, previousVertex);
        addVertex(&lineLength, previousVertex);

        self.hasSignature = YES;

    } else if (state == UIGestureRecognizerStateChanged) {
        CGPoint mid = CGPointMake((CGFloat) ((l.x + previousPoint.x) / 2.0), (CGFloat) ((l.y + previousPoint.y) / 2.0));

        if (distance > QUADRATIC_DISTANCE_TOLERANCE) {
            // Plot quadratic bezier instead of line
            uint32_t i;
            int segments = (int) ((int) distance / 1.5);

            float startPenThickness = previousThickness;
            float endPenThickness = penThickness;
            previousThickness = penThickness;

            for (i = 0; i < segments; i++) {
                penThickness = startPenThickness + ((endPenThickness - startPenThickness) / segments) * i;

                CGPoint quadPoint = quadraticPointInCurve(previousMidPoint, mid, previousPoint, (float) i / (float) (segments));

                SignaturePoint v = viewPointToGL(quadPoint, self.bounds);
                [self addTriangleStripPointsForPrevious:previousVertex next:v];

                previousVertex = v;
            }
        } else if (distance > 1.0) {
            SignaturePoint v = viewPointToGL(l, self.bounds);
            [self addTriangleStripPointsForPrevious:previousVertex next:v];

            previousVertex = v;
            previousThickness = penThickness;
        }

        previousPoint = l;
        previousMidPoint = mid;

    } else if (state == UIGestureRecognizerStateEnded | state == UIGestureRecognizerStateCancelled) {
        previousVertex = viewPointToGL(l, self.bounds);
        addVertex(&lineLength, previousVertex);
        addVertex(&lineLength, previousVertex);
    }

    glBindBuffer(GL_ARRAY_BUFFER, 0);
    [self setNeedsDisplay];
}


#pragma mark - Private -
- (void)bindShaderAttributes {
    glEnableVertexAttribArray(GLKVertexAttribPosition);
    glVertexAttribPointer(GLKVertexAttribPosition, 3, GL_FLOAT, GL_FALSE, sizeof(GPUVertex), 0);

    glEnableVertexAttribArray(GLKVertexAttribColor);
    glVertexAttribPointer(GLKVertexAttribColor, 3, GL_FLOAT, GL_FALSE, sizeof(GPUVertex), (void*) sizeof(GLKVector3));
}

- (void)addTriangleStripPointsForPrevious:(SignaturePoint)previous next:(SignaturePoint)next {
    float toTravel = penThickness / 2.0f;

    for (int i = 0; i < 2; i++) {
        GLKVector3 p = perpendicular(previous, next);
        GLKVector3 ref = GLKVector3Add(next, p);

        float distance = GLKVector3Distance(next, ref);
        float difX = next.x - ref.x;
        float difY = next.y - ref.y;
        float ratio = -1.0f * (toTravel / distance);

        difX = difX * ratio;
        difY = difY * ratio;

        SignaturePoint stripPoint = SignaturePointMake(next.x + difX, next.y + difY, 0.0f);
        addVertex(&lineLength, stripPoint);

        toTravel *= -1;
    }
}

@end
