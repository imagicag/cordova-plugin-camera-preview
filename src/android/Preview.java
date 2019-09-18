package com.cordovaplugincamerapreview;

import android.app.Activity;
import android.content.Context;
import android.hardware.Camera;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.View;
import android.widget.RelativeLayout;

import org.apache.cordova.LOG;

import java.io.IOException;
import java.util.List;

public class Preview extends RelativeLayout implements SurfaceHolder.Callback {

    private static final String TAG = "CameraPreviewLayout";
    public static final String TAG1 = "CameraRatio";
    static final double ASPECT_TOLERANCE = 0.1;
    private static final double DEFAULT_RATIO = 1.33;
    CustomSurfaceView mSurfaceView;
    public SurfaceHolder mHolder;
    Camera.Size mPreviewSize;
    List<Camera.Size> mSupportedPreviewSizes;
    Camera mCamera;
    int cameraId;
    int displayOrientation;
    int facing = Camera.CameraInfo.CAMERA_FACING_BACK;


    public Preview(Context context) {
        super(context);

        mSurfaceView = new CustomSurfaceView(context);
        addView(mSurfaceView);

        requestLayout();

        // Install a SurfaceHolder.Callback so we get notified when the
        // underlying surface is created and destroyed.
        mHolder = mSurfaceView.getHolder();
        mHolder.addCallback(this);
        mHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
    }

    public void setCamera(Camera camera, int cameraId) {
        if (camera != null) {
            mCamera = camera;
            this.cameraId = cameraId;
            mSupportedPreviewSizes = mCamera.getParameters().getSupportedPreviewSizes();
            setCameraDisplayOrientation();

            List<String> mFocusModes = mCamera.getParameters().getSupportedFocusModes();

            Camera.Parameters params = mCamera.getParameters();
            if (mFocusModes.contains("continuous-picture")) {
                params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            } else if (mFocusModes.contains("continuous-video")) {
                params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
            } else if (mFocusModes.contains("auto")) {
                params.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
            }
            mCamera.setParameters(params);
        }
    }

    public int getDisplayOrientation() {
        return displayOrientation;
    }

    public int getCameraFacing() {
        return facing;
    }

    public void printPreviewSize(String from) {
        Log.d(TAG, "printPreviewSize from " + from + ": > width: " + mPreviewSize.width + " height: " + mPreviewSize.height);
    }

    public void setCameraPreviewSize() {
        if (mCamera != null) {
            Camera.Parameters parameters = mCamera.getParameters();
            parameters.setPreviewSize(mPreviewSize.width, mPreviewSize.height);
            mCamera.setParameters(parameters);
        }
    }

    private void setCameraDisplayOrientation() {
        Camera.CameraInfo info = new Camera.CameraInfo();
        int rotation = ((Activity) getContext()).getWindowManager().getDefaultDisplay().getRotation();
        int degrees = 0;
        DisplayMetrics dm = new DisplayMetrics();

        Camera.getCameraInfo(cameraId, info);
        ((Activity) getContext()).getWindowManager().getDefaultDisplay().getMetrics(dm);

        switch (rotation) {
            case Surface.ROTATION_0:
                degrees = 0;
                break;
            case Surface.ROTATION_90:
                degrees = 90;
                break;
            case Surface.ROTATION_180:
                degrees = 180;
                break;
            case Surface.ROTATION_270:
                degrees = 270;
                break;
        }
        facing = info.facing;
        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            displayOrientation = (info.orientation + degrees) % 360;
            displayOrientation = (360 - displayOrientation) % 360;
        } else {
            displayOrientation = (info.orientation - degrees + 360) % 360;
        }

        Log.d(TAG, "screen is rotated " + degrees + "deg from natural");
        Log.d(TAG, (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT ? "front" : "back") + " camera is oriented -" + info.orientation + "deg from natural");
        Log.d(TAG, "need to rotate preview " + displayOrientation + "deg");
        mCamera.setDisplayOrientation(displayOrientation);
    }

    public void switchCamera(Camera camera, int cameraId) {
        try {
            setCamera(camera, cameraId);

            Log.d(TAG, "before set camera");

            camera.setPreviewDisplay(mHolder);

            Log.d(TAG, "before getParameters");

            Camera.Parameters parameters = camera.getParameters();

            Log.d(TAG, "before setPreviewSize");

            mSupportedPreviewSizes = parameters.getSupportedPreviewSizes();
            Log.d(TAG1, "switchCamera");
            mPreviewSize = getOptimalPreviewSize(mSupportedPreviewSizes, mSurfaceView.getWidth(), mSurfaceView.getHeight());
            parameters.setPreviewSize(mPreviewSize.width, mPreviewSize.height);
            Log.d(TAG, mPreviewSize.width + " " + mPreviewSize.height);

            camera.setParameters(parameters);
        } catch (IOException exception) {
            Log.e(TAG, exception.getMessage());
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // We purposely disregard child measurements because act as a
        // wrapper to a SurfaceView that centers the camera preview instead
        // of stretching it.
        final int width = resolveSize(getSuggestedMinimumWidth(), widthMeasureSpec);
        final int height = resolveSize(getSuggestedMinimumHeight(), heightMeasureSpec);
        setMeasuredDimension(width, height);

        if (mSupportedPreviewSizes != null) {
            Log.d(TAG1, "Preview onMeasure");
            mPreviewSize = getOptimalPreviewSize(mSupportedPreviewSizes, width, height);
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {

        if (changed && getChildCount() > 0) {
            final View child = getChildAt(0);

            int width = r - l;
            int height = b - t;

            int previewWidth = width;
            int previewHeight = height;

            if (mPreviewSize != null) {
                previewWidth = mPreviewSize.width;
                previewHeight = mPreviewSize.height;

                if (displayOrientation == 90 || displayOrientation == 270) {
                    previewWidth = mPreviewSize.height;
                    previewHeight = mPreviewSize.width;
                }

                LOG.d(TAG, "previewWidth:" + previewWidth + " previewHeight:" + previewHeight);
            }

            int nW;
            int nH;
            int top;
            int left;

            float scale = 1.0f;

            // Center the child SurfaceView within the parent.
            if (width * previewHeight < height * previewWidth) {
                Log.d(TAG, "center horizontally");
                int scaledChildWidth = (int) ((previewWidth * height / previewHeight) * scale);
                nW = (width + scaledChildWidth) / 2;
                nH = (int) (height * scale);
                top = 0;
                left = (width - scaledChildWidth) / 2;
            } else {
                Log.d(TAG, "center vertically");
                int scaledChildHeight = (int) ((previewHeight * width / previewWidth) * scale);
                nW = (int) (width * scale);
                nH = (height + scaledChildHeight) / 2;
                top = (height - scaledChildHeight) / 2;
                left = 0;
            }
            child.layout(left, top, nW, nH);

            Log.d("layout", "left:" + left);
            Log.d("layout", "top:" + top);
            Log.d("layout", "right:" + nW);
            Log.d("layout", "bottom:" + nH);
        }
    }

    public void surfaceCreated(SurfaceHolder holder) {
        // The Surface has been created, acquire the camera and tell it where
        // to draw.
        try {
            if (mCamera != null) {
                mSurfaceView.setWillNotDraw(false);
                mCamera.setPreviewDisplay(holder);
            }
        } catch (Exception exception) {
            Log.e(TAG, "Exception caused by setPreviewDisplay()", exception);
        }
    }

    public void surfaceDestroyed(SurfaceHolder holder) {
        // Surface will be destroyed when we return, so stop the preview.
//    try {
//      if (mCamera != null) {
//        mCamera.stopPreview();
//      }
//    } catch (Exception exception) {
//      Log.e(TAG, "Exception caused by surfaceDestroyed()", exception);
//    }
    }

    private Camera.Size getOptimalPreviewSize(List<Camera.Size> sizes, int width, int height) {
        Log.d(TAG1, "--------GET OPTIMAL PREVIEW SIZE-----)");
        Log.d(TAG, "width: " + width + " height: " + height);
        if (sizes == null) {
            return null;
        }

        double targetRatio = calculateTargetRatio(displayOrientation, width, height);
        Log.d(TAG, "targetRatio " + targetRatio);
        Camera.Size optimalSize = null;
        double minDiff = Double.MAX_VALUE;

        // Try to find an size match aspect ratio and size
        for (Camera.Size size : sizes) {
            double ratio = (double) size.width / size.height;
            if (Math.abs(ratio - targetRatio) > ASPECT_TOLERANCE) continue;
            if (Math.abs(size.height - height) < minDiff) {
                optimalSize = size;
                minDiff = Math.abs(size.height - height);
            }
        }
        // Cannot find the one match the aspect ratio, ignore the requirement
        if (optimalSize == null) {
            Log.d(TAG, "optimalSize == null after search with ASPECT_TOLERANCE");
            minDiff = Double.MAX_VALUE;
            for (Camera.Size size : sizes) {
                if (Math.abs(size.height - height) < minDiff) {
                    optimalSize = size;
                    minDiff = Math.abs(size.height - height);
                }
            }
        } else {
            Log.d(TAG, "GOT optimal preview size in First Search: width: " + optimalSize.width + " height: " + optimalSize.height);
        }

        double finalRatio = (double) optimalSize.width / optimalSize.height;
        Log.d(TAG1, "-------- FINAL width: " + optimalSize.width + " height: " + optimalSize.height + " ration " + finalRatio + " ---------");
        return optimalSize;
    }

    private double calculateTargetRatio(int displayOrientation, int width, int height) {
        if (width == 0 || height == 0) {
            return Preview.DEFAULT_RATIO;
        } else {
            boolean wasRotated = displayOrientation == 90 || displayOrientation == 270;
            return (wasRotated ? (double) height / width : (double) width / height);
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
        if (mCamera != null) {
            try {
                // Now that the size is known, set up the camera parameters and begin
                // the preview.
                mSupportedPreviewSizes = mCamera.getParameters().getSupportedPreviewSizes();
                if (mSupportedPreviewSizes != null) {
                    Log.d(TAG1, "! preview surfaceChanged");
                    mPreviewSize = getOptimalPreviewSize(mSupportedPreviewSizes, w, h);
                }
                Camera.Parameters parameters = mCamera.getParameters();
                parameters.setPreviewSize(mPreviewSize.width, mPreviewSize.height);
                requestLayout();
                mCamera.stopPreview();
                mCamera.setParameters(parameters);
                mCamera.startPreview();
            } catch (Exception exception) {
                Log.e(TAG, "Exception caused by surfaceChanged()", exception);
            }
        }
    }

    public void setOneShotPreviewCallback(Camera.PreviewCallback callback) {
        if (mCamera != null) {
            mCamera.setOneShotPreviewCallback(callback);
        }
    }
}
