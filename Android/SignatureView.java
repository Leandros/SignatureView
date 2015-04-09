package dev.arvid.signatureview;

import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.support.v4.view.MotionEventCompat;
import android.support.v4.view.VelocityTrackerCompat;
import android.util.Log;
import android.view.MotionEvent;
import android.view.VelocityTracker;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.Semaphore;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;


public class SignatureView extends GLSurfaceView {
    private static final float STROKE_WIDTH_MIN = 0.006f;
    private static final float STROKE_WIDTH_MAX = 0.040f;
    private static final float STROKE_WIDTH_SMOOTHING = 0.5f;

    private static final int VELOCITY_CLAMP_MIN = 20;
    private static final int VELOCITY_CLAMP_MAX = 5000;
    private static final int QUADRATIC_DISTANCE_TOLERANCE = 3;
    private static final int MAX_VERTICES = 100000;

    private static final int STATE_BEGAN = 0;
    private static final int STATE_MOVED = 1;
    private static final int STATE_ENDED = 2;

    private final SignatureRenderer mRenderer;

    private float penThickness = 0.003f;
    private float prevPenThickness = 0.0f;
    private Point previousPoint = new Point();
    private Point previousMidPoint = new Point();
    private Point previousVertex = new Point();

    private VelocityTracker mVelocityTracker = null;
    private boolean hasSignature = false;

    public SignatureView(Context context) {
        super(context);

        setEGLContextClientVersion(2);

        mRenderer = new SignatureRenderer();
        setRenderer(mRenderer);
        setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
    }

    public void erase() {
        mRenderer.lines.verticesCount = 0;
        mRenderer.dots.verticesCount = 0;
        hasSignature = false;
        requestRender();
    }

    public Bitmap getImage() throws InterruptedException {
        if (!hasSignature) return null;

        final Semaphore semaphore = new Semaphore(0);
        final int width = getMeasuredWidth();
        final int height = getMeasuredHeight();

        final byte[] finalPixels = new byte[width * height];
        mRenderer.runOnDrawEnd(new Runnable() {
            @Override
            public void run() {
                final ByteBuffer pixelBuffer = ByteBuffer.allocate(width * height);
                GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixelBuffer);
                byte[] pixelArray = pixelBuffer.array();

                for (int i = 0; i < height; i++) {
                    for (int j = 0; j < width; j++) {
                        finalPixels[(height - i - 1) * width + j] = pixelArray[i * width + j];
                    }
                }

                semaphore.release();
            }
        });

        semaphore.acquire();

        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(finalPixels));

        return bitmap;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event){
        final int index = 0;
        final int action = MotionEventCompat.getActionMasked(event);
        final int pointerId = event.getPointerId(index);
        switch(action) {
            case (MotionEvent.ACTION_DOWN):
                if (mVelocityTracker == null) { mVelocityTracker = VelocityTracker.obtain();
                } else {
                    mVelocityTracker.clear();
                }

                mVelocityTracker.addMovement(event);

                drawPan(STATE_BEGAN, MotionEventCompat.getX(event, pointerId),
                                     MotionEventCompat.getY(event, pointerId),
                                     0.0f, 0.0f);
                return true;

            case (MotionEvent.ACTION_MOVE):
                mVelocityTracker.addMovement(event);
                mVelocityTracker.computeCurrentVelocity(1000);

                drawPan(STATE_MOVED, MotionEventCompat.getX(event, pointerId),
                                     MotionEventCompat.getY(event, pointerId),
                                     VelocityTrackerCompat.getXVelocity(mVelocityTracker, pointerId),
                                     VelocityTrackerCompat.getYVelocity(mVelocityTracker, pointerId));
                return true;

            case (MotionEvent.ACTION_UP):
            case (MotionEvent.ACTION_CANCEL):
                drawPan(STATE_ENDED, MotionEventCompat.getX(event, pointerId),
                                     MotionEventCompat.getY(event, pointerId),
                                     VelocityTrackerCompat.getXVelocity(mVelocityTracker, pointerId),
                                     VelocityTrackerCompat.getYVelocity(mVelocityTracker, pointerId));

                mVelocityTracker.recycle();
                mVelocityTracker = null;
                return true;

            default:
                return super.onTouchEvent(event);
        }
    }

    private void drawPan(int state, float locationX, float locationY, float velocityX, float velocityY) {
        Size size = new Size(getMeasuredWidth(), getMeasuredHeight());

        float velocityMagnitude = (float) Math.sqrt(velocityX * velocityX + velocityY * velocityY);
        float clampedVelocityMagnitude = clamp(VELOCITY_CLAMP_MIN, VELOCITY_CLAMP_MAX, velocityMagnitude);
        float normalizedVelocity = (clampedVelocityMagnitude - VELOCITY_CLAMP_MIN) / (VELOCITY_CLAMP_MAX - VELOCITY_CLAMP_MIN);
        float lowPassFilterAlpha = STROKE_WIDTH_SMOOTHING;
        float newThickness = (STROKE_WIDTH_MAX - STROKE_WIDTH_MIN) * normalizedVelocity + STROKE_WIDTH_MIN;
        float distance = 0.0f;
        penThickness = penThickness * lowPassFilterAlpha + newThickness * (1 - lowPassFilterAlpha);
        if (previousPoint.x > 0.0f) {
            distance = (float) Math.sqrt((locationX - previousPoint.x) * (locationX - previousPoint.x)
                                       + (locationY - previousPoint.y) * (locationY - previousPoint.y));
        }

        if (state == STATE_BEGAN) {
            previousPoint = new Point(locationX, locationY);
            previousMidPoint = previousPoint;

            previousVertex = viewPointToGL(previousPoint, size);
            prevPenThickness = penThickness;

            mRenderer.lines.addVertex(previousVertex.x, previousVertex.y);
            mRenderer.lines.addVertex(previousVertex.x, previousVertex.y);

            hasSignature = true;
        } else if (state == STATE_MOVED) {
            Point mid = new Point(((locationX + previousPoint.x) / 2.0f), ((locationY + previousPoint.y) / 2.0f));

            if (distance > QUADRATIC_DISTANCE_TOLERANCE) {
                int i;
                int segments = (int) (distance / 1.5f);
                float startPenThickness = prevPenThickness;
                float endPenThickness = penThickness;
                prevPenThickness = penThickness;

                for (i = 0; i < segments; i++) {
                    Point quad, v;

                    penThickness = startPenThickness + ((endPenThickness - startPenThickness) / segments) * i;
                    quad = quadraticPointInCurve(previousMidPoint, mid, previousPoint, (float) i / (float) (segments));
                    v = viewPointToGL(quad, size);
                    addTriangleStripPointsForPrevious(previousVertex, v);

                    previousVertex = v;
                }
            } else if (distance > 1.0) {
                Point v = viewPointToGL(new Point(locationX, locationY), size);
                addTriangleStripPointsForPrevious(previousVertex, v);

                previousVertex = v;
                prevPenThickness = penThickness;
            }

            previousPoint = new Point(locationX, locationY);
            previousMidPoint = mid;

        } else if (state == STATE_ENDED) {
            Point v = viewPointToGL(new Point(locationX, locationY), size);
            mRenderer.lines.addVertex(v.x, v.y);
            mRenderer.lines.addVertex(v.x, v.y);

            previousVertex = v;
        }

        requestRender();
    }

    private void drawTap(float locationX, float locationY) {
        int i, segments = 20;
        float angle = 0.0f;
        Point radius = new Point(penThickness * 2.0f * generateRandom(0.5f, 1.5f),
                                 penThickness * 2.0f * generateRandom(0.5f, 1.5f));

        Point point = viewPointToGL(new Point(locationX, locationY),
                new Size(getMeasuredWidth(), getMeasuredHeight()));
        mRenderer.dots.addVertex(point.x, point.y);
        mRenderer.dots.addVertex(point.x, point.y);

        for (i = 0; i <= segments; i++) {
            Point p = new Point();
            p.x += radius.x * Math.cos(angle);
            p.y += radius.y * Math.sin(angle);

            mRenderer.dots.addVertex(p.x, p.y);
            mRenderer.dots.addVertex(point.x, point.y);

            angle += Math.PI * 2.0f / segments;
        }

        mRenderer.dots.addVertex(point.x, point.y);
        requestRender();
    }

    private float clamp(float x, float y, float z) {
        return Math.max(x, Math.min(y, z));
    }
    private float generateRandom(float from, float to) {
        return (float) (Math.random() % 10000 / 10000.0 * (to - from) + from);
    }

    private Point quadraticPointInCurve(Point start, Point end, Point controlPoint, float percent) {
        double a = Math.pow((1.0 - percent), 2.0);
        double b = 2.0f * percent * (1.0 - percent);
        double c = Math.pow(percent, 2.0);

        return new Point((float) (a * start.x + b * controlPoint.x + c * end.x),
                         (float) (a * start.y + b * controlPoint.y + c * end.y));
    }

    private void addTriangleStripPointsForPrevious(Point prev, Point next) {
        float toTravel = penThickness / 2.0f;
        for (int i = 0; i < 2; i++) {
            Point p = perpendicular(prev, next);
            Point ref = pointAdd(next, p);

            float distance = pointDistance(next, ref);
            float difX = next.x - ref.x;
            float difY = next.y - ref.y;
            float ratio = -1.0f * (toTravel / distance);

            difX = difX * ratio;
            difY = difY * ratio;

            mRenderer.lines.addVertex(next.x + difX, next.y + difY);
            toTravel *= -1;
        }
    }

    private Point perpendicular(Point p1, Point p2) {
        Point retVal = new Point();
        retVal.x = p2.y - p1.y;
        retVal.y = -1 * (p2.x - p1.x);

        return retVal;
    }

    private Point pointAdd(Point p1, Point p2) {
        Point retVal = new Point();
        retVal.x = p1.x + p2.x;
        retVal.y = p1.y + p2.y;

        return retVal;
    }

    private Point pointSubtract(Point p1, Point p2) {
        Point retVal = new Point();
        retVal.x = p1.x - p2.x;
        retVal.y = p1.y - p2.y;

        return retVal;
    }

    private float pointLength(Point p) {
        return (float) Math.sqrt(p.x * p.x + p.y * p.y);
    }

    private float pointDistance(Point p1, Point p2) {
        return pointLength(pointSubtract(p1, p2));
    }

    private Point viewPointToGL(Point viewPoint, Size size) {
        Point retVal = new Point();
        retVal.x = ((viewPoint.x / size.width) * 2.0f - 1.0f); /* add * -1.0f if using mvp matrices */
        retVal.y = ((viewPoint.y / size.height) * 2.0f - 1.0f) * -1.0f;

        return retVal;
    }

    private class SignatureRenderer implements GLSurfaceView.Renderer {
        public Vertices lines;
        public Vertices dots;

        private final Queue<Runnable> runOnDrawEnd = new LinkedList<>();
        private final float[] mvpMatrix = new float[16];
        private final float[] projectionMatrix = new float[16];
        private final float[] viewMatrix = new float[16];

        @Override
        public void onSurfaceCreated(GL10 gl10, EGLConfig eglConfig) {
            GLES20.glDisable(GLES20.GL_DEPTH_TEST);
            GLES20.glClearColor(0.5f, 0.2f, 0.8f, 1.0f);

            lines = new Vertices(MAX_VERTICES);
            dots = new Vertices(MAX_VERTICES);
        }

        @Override
        public void onSurfaceChanged(GL10 gl10, int width, int height) {
            GLES20.glViewport(0, 0, width, height);

            /* not used */
            /* float ratio = (float) width / height;
            Matrix.frustumM(projectionMatrix, 0, -ratio, ratio, -1, 1, 3, 7); */
        }

        @Override
        public void onDrawFrame(GL10 gl10) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

            /* not used */
            /* Matrix.orthoM(projectionMatrix, 0, -1, 1, -1, 1, 0.1f, 2.0f);
            Matrix.translateM(viewMatrix, 0, 0.0f, 0.0f, -1.0f);
            Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, viewMatrix, 0); */
            lines.draw(mvpMatrix);
            dots.draw(mvpMatrix);

            runQueue(runOnDrawEnd);
        }

        private void runOnDrawEnd(Runnable runnable) {
            synchronized (runOnDrawEnd) {
                runOnDrawEnd.add(runnable);
            }
        }
        private void runQueue(Queue<Runnable> queue) {
            synchronized (queue) {
                while (!queue.isEmpty()) {
                    queue.poll().run();
                }
            }
        }
    }

    private class Point {
        public Point() {
            this.x = 0.0f;
            this.y = 0.0f;
        }

        public Point(float x, float y) {
            this.x = x;
            this.y = y;
        }

        public float x;
        public float y;
    }

    private class Size {
        public Size() {
            this.width = 0.0f;
            this.height = 0.0f;
        }

        public Size(float w, float h) {
            this.width = w;
            this.height = h;
        }

        public float width;
        public float height;
    }

    private class Vertices {
        private int program;
        private FloatBuffer vertices;
        private int verticesCount = 0;

        private int positionLocation;
        private int matrixLocation;
        private int colorLocation;

        private final String vertexShaderCode =
                "uniform mat4 uMVPMatrix;" +
                "attribute vec4 vPosition;" +
                "void main() {" +
                "  gl_Position = vPosition;" +
                "}";

        private final String fragmentShaderCode =
                "precision mediump float;" +
                "uniform vec4 vColor;" +
                "void main() {" +
                "  gl_FragColor = vColor;" +
                "}";

        private final float[] color = { 1.0f, 1.0f, 1.0f, 0.0f };

        public Vertices(int size) {
            program = createProgram(createShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode),
                    createShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode));

            vertices = allocFloatBuffer((3 * size) * 4);
            for (int i = 0; i < (3 * size); i++)
                vertices.put(0.0f);
            vertices.position(0);

            /* "conventional" */
            /* GLES20.glGenBuffers(1, VBO, 0); */
            /* GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, VBO[0]); */
            /* GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, (3 * size) * 4, vertices, GLES20.GL_DYNAMIC_DRAW); */
            /* GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0); */
        }

        public void draw(float[] mvpMatrix) {
            vertices.position(0);
            GLES20.glUseProgram(program);

            positionLocation = GLES20.glGetAttribLocation(program, "vPosition");
            GLES20.glVertexAttribPointer(positionLocation, 3, GLES20.GL_FLOAT, false, 0, 0);
            GLES20.glEnableVertexAttribArray(positionLocation);
            GLES20.glVertexAttribPointer(
                    positionLocation, 3,
                    GLES20.GL_FLOAT, false,
                    3 * 4, vertices);

            matrixLocation = GLES20.glGetUniformLocation(program, "uMVPMatrix");
            GLES20.glUniformMatrix4fv(matrixLocation, 1, false, mvpMatrix, 0);

            colorLocation = GLES20.glGetUniformLocation(program, "vColor");
            GLES20.glUniform4fv(colorLocation, 1, color, 0);

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, verticesCount / 3);

            GLES20.glDisableVertexAttribArray(positionLocation);
        }

        public void addVertex(float x, float y) {
            if (Float.isNaN(x) || Float.isInfinite(x)
             || Float.isNaN(y) || Float.isInfinite(y)) return;

            vertices.put(verticesCount, x);
            vertices.put(verticesCount + 1, y);
            vertices.put(verticesCount + 2, 0.0f);
            verticesCount += 3;

            /* "conventional" */
            /* GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, VBO[0]); */
            /* GLES20.glBufferSubData(GLES20.GL_ARRAY_BUFFER, 0, verticesCount * 4, vertices); */
            /* GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0); */
        }
    }

    private static int createProgram(int vShader, int fShader) {
        int[] success = new int[1];
        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vShader);
        GLES20.glAttachShader(program, fShader);
        GLES20.glLinkProgram(program);

        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, success, 0);
        if (success[0] != GLES20.GL_TRUE) {
            String err = GLES20.glGetProgramInfoLog(program);
            Log.e("glProgram", "Program Error!");
            Log.e("glProgram", err);
        }

        return program;
    }

    private static int createShader(int type, String src) {
        int[] success = new int[1];
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, src);
        GLES20.glCompileShader(shader);

        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, success, 0);
        if (success[0] != GLES20.GL_TRUE) {
            String err = GLES20.glGetShaderInfoLog(shader);
            Log.e("glShader", "Shader ERROR!");
            Log.e("glShader", err);
        }

        return shader;
    }

    private static FloatBuffer allocFloatBuffer(int size) {
        ByteBuffer retVal = ByteBuffer.allocateDirect(size);
        retVal.order(ByteOrder.nativeOrder());

        return retVal.asFloatBuffer();
    }
}

