package com.cordovaplugincamerapreview;

import android.app.Activity;
import android.app.Fragment;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.hardware.Camera.PictureCallback;
import android.hardware.Camera.ShutterCallback;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.support.annotation.Nullable;
import android.support.media.ExifInterface;
import android.util.Base64;
import android.util.Log;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.RelativeLayout;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import io.ionic.starter.R;

import static com.bitpay.cordova.qrscanner.QRScanner.currentCameraId;

public class CameraFragment extends Fragment {

    public interface CameraPreviewListener {
        void onPictureTaken(String originalPicture);

        void onPictureTakenError(String message);

        void onSnapshotTaken(String originalPicture);

        void onSnapshotTakenError(String message);

        void onFocusSet(int pointX, int pointY);

        void onFocusSetError(String message);

        void onBackButton();

        void onCameraStarted();
    }

    private CameraPreviewListener eventListener;
    private static final String TAG = "CameraFragment";
    public FrameLayout mainLayout;
    public FrameLayout frameContainerLayout;

    private Preview mPreview;
    public static boolean canTakePicture = true;

    private View view;
    private Camera.Parameters cameraParameters;
    private Camera mCamera;
    private int numberOfCameras;
    private int cameraCurrentlyLocked;
    private int currentQuality;

    // The first rear facing camera
    private int defaultCameraId;
    public String defaultCamera;
    public boolean tapToTakePicture;
    public boolean dragEnabled;
    public boolean tapToFocus;
    public boolean disableExifHeaderStripping;
    public boolean storeToFile;
    public boolean toBack;

    public int width;
    public int height;
    public int x;
    public int y;

    public void setEventListener(CameraPreviewListener listener) {
        eventListener = listener;
    }

    private String appResourcesPackage;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        appResourcesPackage = getActivity().getPackageName();

        // Inflate the layout for this fragment
        view = inflater.inflate(getResources().getIdentifier("camera_activity", "layout", appResourcesPackage), container, false);
        createCameraPreview();
        return view;
    }

    public void setRect(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    PictureCallback jpegPictureCallback = new PictureCallback() {
        @Override
        public void onPictureTaken(byte[] data, Camera arg1) {
            try {
                if (!disableExifHeaderStripping) {
                    Matrix matrix = new Matrix();
                    if (cameraCurrentlyLocked == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                        matrix.preScale(1.0f, -1.0f);
                    }

                    ExifInterface exifInterface = new ExifInterface(new ByteArrayInputStream(data));
                    int rotation = exifInterface.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                    int rotationInDegrees = exifToDegrees(rotation);

                    if (rotation != 0f) {
                        matrix.preRotate(rotationInDegrees);
                    }

                    // Check if matrix has changed. In that case, apply matrix and override data
                    if (!matrix.isIdentity()) {
                        Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
                        bitmap = applyMatrix(bitmap, matrix);
                        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                        bitmap.compress(Bitmap.CompressFormat.JPEG, currentQuality, outputStream);
                        data = outputStream.toByteArray();
                    }
                }

                WeakReference<SavePhotoTask.IPhotoPathProducer> savePhotoCallback = new WeakReference<>(
                        path -> {
                            if (isAdded() && !isRemoving()) {
                                eventListener.onPictureTaken(path);
                            }
                        });
                SavePhotoTask.Args args = new SavePhotoTask.Args(data, getActivity().getCacheDir(), storeToFile);
                new SavePhotoTask(savePhotoCallback).execute(args);
                Log.d(TAG, "CameraPreview pictureTakenHandler called back");
            } catch (OutOfMemoryError e) {
                // most likely failed to allocate memory for rotateBitmap
                Log.e(TAG, "OoME ))", e);
                // failed to allocate memory
                eventListener.onPictureTakenError("Picture too large (memory)");
            } catch (IOException e) {
                Log.e(TAG, "CameraPreview IOException", e);
                eventListener.onPictureTakenError("IO Error when extracting exif");
            } catch (Exception e) {
                Log.e(TAG, "CameraPreview onPictureTaken general exception", e);
            } finally {
                canTakePicture = true;
                mCamera.stopPreview();
                try {
                    mCamera.setPreviewDisplay(null);
                } catch (IOException e) {
                    Log.e(TAG, "Failed to remove a preview display", e);
                }
                mCamera.release();
            }
        }
    };

    private void setupTouchAndBackButton() {

        final GestureDetector gestureDetector =
                new GestureDetector(getActivity().getApplicationContext(), new TapGestureDetector());

        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                frameContainerLayout.setClickable(true);
                frameContainerLayout.setOnTouchListener(
                        new View.OnTouchListener() {

                            private int mLastTouchX;
                            private int mLastTouchY;
                            private int mPosX = 0;
                            private int mPosY = 0;

                            @Override
                            public boolean onTouch(View v, MotionEvent event) {
                                FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) frameContainerLayout.getLayoutParams();


                                boolean isSingleTapTouch = gestureDetector.onTouchEvent(event);
                                if (event.getAction() != MotionEvent.ACTION_MOVE && isSingleTapTouch) {
                                    if (tapToTakePicture && tapToFocus) {
                                        setFocusArea((int) event.getX(0), (int) event.getY(0), new Camera.AutoFocusCallback() {
                                            public void onAutoFocus(boolean success, Camera camera) {
                                                if (success) {
                                                    takePicture(0, 0, 85);
                                                } else {
                                                    Log.d(TAG, "onTouch:" + " setFocusArea() did not succeed");
                                                }
                                            }
                                        });

                                    } else if (tapToTakePicture) {
                                        takePicture(0, 0, 85);

                                    } else if (tapToFocus) {
                                        setFocusArea((int) event.getX(0), (int) event.getY(0), new Camera.AutoFocusCallback() {
                                            public void onAutoFocus(boolean success, Camera camera) {
                                                if (success) {
                                                    // A callback to JS might make sense here.
                                                } else {
                                                    Log.d(TAG, "onTouch:" + " setFocusArea() did not suceed");
                                                }
                                            }
                                        });
                                    }
                                    return true;
                                } else {
                                    if (dragEnabled) {
                                        int x;
                                        int y;

                                        switch (event.getAction()) {
                                            case MotionEvent.ACTION_DOWN:
                                                if (mLastTouchX == 0 || mLastTouchY == 0) {
                                                    mLastTouchX = (int) event.getRawX() - layoutParams.leftMargin;
                                                    mLastTouchY = (int) event.getRawY() - layoutParams.topMargin;
                                                } else {
                                                    mLastTouchX = (int) event.getRawX();
                                                    mLastTouchY = (int) event.getRawY();
                                                }
                                                break;
                                            case MotionEvent.ACTION_MOVE:

                                                x = (int) event.getRawX();
                                                y = (int) event.getRawY();

                                                final float dx = x - mLastTouchX;
                                                final float dy = y - mLastTouchY;

                                                mPosX += dx;
                                                mPosY += dy;

                                                layoutParams.leftMargin = mPosX;
                                                layoutParams.topMargin = mPosY;

                                                frameContainerLayout.setLayoutParams(layoutParams);

                                                // Remember this touch position for the next move event
                                                mLastTouchX = x;
                                                mLastTouchY = y;

                                                break;
                                            default:
                                                break;
                                        }
                                    }
                                }
                                return true;
                            }
                        });
                frameContainerLayout.setFocusableInTouchMode(true);
                frameContainerLayout.requestFocus();
                frameContainerLayout.setOnKeyListener(new android.view.View.OnKeyListener() {
                    @Override
                    public boolean onKey(android.view.View v, int keyCode, android.view.KeyEvent event) {

                        if (keyCode == android.view.KeyEvent.KEYCODE_BACK) {
                            eventListener.onBackButton();
                            return true;
                        }
                        return false;
                    }
                });
            }
        });

    }

    private void setDefaultCameraId() {
        // Find the total number of cameras available
        numberOfCameras = Camera.getNumberOfCameras();

        int facing = "front".equals(defaultCamera) ? Camera.CameraInfo.CAMERA_FACING_FRONT : Camera.CameraInfo.CAMERA_FACING_BACK;

        // Find the ID of the default camera
        Camera.CameraInfo cameraInfo = new Camera.CameraInfo();
        for (int i = 0; i < numberOfCameras; i++) {
            Camera.getCameraInfo(i, cameraInfo);
            if (cameraInfo.facing == facing) {
                defaultCameraId = i;
                break;
            }
        }
    }

    @Override
    public void onResume() {
        super.onResume();

        mCamera = Camera.open(defaultCameraId);

        if (cameraParameters != null) {
            mCamera.setParameters(cameraParameters);
        }

        cameraCurrentlyLocked = defaultCameraId;

        if (mPreview.mPreviewSize == null) {
            mPreview.setCamera(mCamera, cameraCurrentlyLocked);
            eventListener.onCameraStarted();
        } else {
            mPreview.switchCamera(mCamera, cameraCurrentlyLocked);
        }
        setupFrameContainer();

    }

    private void setupFrameContainer() {
        final FrameLayout frameContainerLayout = view.findViewById(getResources().getIdentifier("frame_container", "id", appResourcesPackage));

        ViewTreeObserver viewTreeObserver = frameContainerLayout.getViewTreeObserver();

        if (viewTreeObserver.isAlive()) {
            viewTreeObserver.addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                @Override
                public void onGlobalLayout() {
                    frameContainerLayout.getViewTreeObserver().removeGlobalOnLayoutListener(this);
                    frameContainerLayout.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
                    Activity activity = getActivity();
                    if (isAdded() && activity != null) {
                        final RelativeLayout frameCamContainerLayout = view.findViewById(getResources().getIdentifier("frame_camera_cont", "id", appResourcesPackage));

                        FrameLayout.LayoutParams camViewLayout = new FrameLayout.LayoutParams(frameContainerLayout.getWidth(), frameContainerLayout.getHeight());
                        camViewLayout.gravity = Gravity.CENTER_HORIZONTAL | Gravity.CENTER_VERTICAL;
                        frameCamContainerLayout.setLayoutParams(camViewLayout);
                    }
                }
            });
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        // Because the Camera object is a shared resource, it's very important to release it when the activity is paused.
        if (mCamera != null) {
            setDefaultCameraId();
            mPreview.setCamera(null, -1);
            mCamera.release();
            mCamera = null;
        }
    }

    public Camera getCamera() {
        return mCamera;
    }

    public void switchCamera() {
        // check for availability of multiple cameras
        if (numberOfCameras == 1) {
            //There is only one camera available
        } else {
            Log.d(TAG, "numberOfCameras: " + numberOfCameras);

            // OK, we have multiple cameras. Release this camera -> cameraCurrentlyLocked
            if (mCamera != null) {
                mCamera.stopPreview();
                mPreview.setCamera(null, -1);
                mCamera.release();
                mCamera = null;
            }

            Log.d(TAG, "cameraCurrentlyLocked := " + Integer.toString(cameraCurrentlyLocked));
            try {
                cameraCurrentlyLocked = (cameraCurrentlyLocked + 1) % numberOfCameras;
                Log.d(TAG, "cameraCurrentlyLocked new: " + cameraCurrentlyLocked);
            } catch (Exception e) {
                Log.e(TAG, "Failed to switch camera", e);
            }

            // Acquire the next camera and request Preview to reconfigure parameters.
            mCamera = Camera.open(cameraCurrentlyLocked);

            if (cameraParameters != null) {
                Log.d(TAG, "camera parameter not null");

                // Check for flashMode as well to prevent error on frontward facing camera.
                List<String> supportedFlashModesNewCamera = mCamera.getParameters().getSupportedFlashModes();
                String currentFlashModePreviousCamera = cameraParameters.getFlashMode();
                if (supportedFlashModesNewCamera != null && supportedFlashModesNewCamera.contains(currentFlashModePreviousCamera)) {
                    Log.d(TAG, "current flash mode supported on new camera. setting params");
         /* mCamera.setParameters(cameraParameters);
            The line above is disabled because parameters that can actually be changed are different
            from one device to another. Makes less sense trying to reconfigure them when changing camera device
            while those settings gan be changed using plugin methods.
         */
                } else {
                    Log.d(TAG, "current flash mode NOT supported on new camera");
                    cameraParameters.setFlashMode(Camera.Parameters.FLASH_MODE_OFF);
//                    mCamera.setParameters(cameraParameters);
                }

            } else {
                Log.d(TAG, "camera parameter NULL");
            }

            mPreview.switchCamera(mCamera, cameraCurrentlyLocked);

            mCamera.startPreview();
        }
    }

    public void setCameraParameters(Camera.Parameters params) {
        if (!isAdded() || isRemoving()) {
            return;
        }
        cameraParameters = params;
        if (mCamera != null && cameraParameters != null) {
            mCamera.setParameters(cameraParameters);
        }
    }

    public boolean hasFrontCamera() {
        return getActivity().getApplicationContext().getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT);
    }

    public static Bitmap applyMatrix(Bitmap source, Matrix matrix) {
        return Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);
    }

    ShutterCallback shutterCallback = new ShutterCallback() {
        public void onShutter() {
            // do nothing, availabilty of this callback causes default system shutter sound to work
        }
    };

    private static int exifToDegrees(int exifOrientation) {
        if (exifOrientation == ExifInterface.ORIENTATION_ROTATE_90) {
            return 90;
        } else if (exifOrientation == ExifInterface.ORIENTATION_ROTATE_180) {
            return 180;
        } else if (exifOrientation == ExifInterface.ORIENTATION_ROTATE_270) {
            return 270;
        }
        return 0;
    }

    private void createCameraPreview() {
        if (mPreview == null) {
            setDefaultCameraId();

            //set box position and size
            FrameLayout.LayoutParams layoutParams = new FrameLayout.LayoutParams(width, height);
            layoutParams.setMargins(x, y, 0, 0);


            frameContainerLayout = view.findViewById(R.id.frame_container);
            frameContainerLayout.setLayoutParams(layoutParams);
            //video view
            mPreview = new Preview(getActivity());
            mainLayout = view.findViewById(R.id.video_view);
            mainLayout.setLayoutParams(new RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.MATCH_PARENT));
            mainLayout.addView(mPreview);
            mainLayout.setEnabled(false);

            if (!toBack) {
                this.setupTouchAndBackButton();
            }

        }
    }

    private Camera.Size prepareRequestedSize(final int width, final int height) {
        int newWidth = width;
        int newHeight = height;

        // convert to landscape if necessary
        if (width < height) {
            newWidth = height;
            newHeight = width;
        }

        return mCamera.new Size(newWidth, newHeight);
    }

    private double prepareAspectRatio(Camera.Size size) {
        double previewAspectRatio = (double) size.width / (double) size.height;

        if (previewAspectRatio < 1.0) {
            // reset ratio to landscape
            Log.d(TAG, "reset ratio to landscape ");
            previewAspectRatio = 1.0 / previewAspectRatio;
        }
        return previewAspectRatio;
    }

    private Camera.Size getOptimalPictureSize(final int width, final int height, final Camera.Size previewSize, final List<Camera.Size> supportedSizes) {
        Log.d(TAG, "----GET OPTIMAL PICTURE SIZE 2----");
        Log.d(TAG, "INPUT PARAMS width : " + width + " height " + height + " preview.width " + previewSize.width + " preview.height " + previewSize.height);
        Camera.Size optimalSize = null;
        Camera.Size requestedSize = prepareRequestedSize(width, height);
        Log.d(TAG, "requested SIZE width: " + requestedSize.width + " previewSize.height: " + requestedSize.height);
        double previewAspectRatio = prepareAspectRatio(previewSize);
        double ratioMinDelta = Preview.ASPECT_TOLERANCE;
        Log.d(TAG, "CameraPreview previewAspectRatio " + String.format("%.02f", previewAspectRatio));
        while (optimalSize == null && ratioMinDelta <= 1f) {
            Log.d(TAG, "!!!!!! ratioMinDelta " + ratioMinDelta);
            optimalSize = findOptimalSizeByClosestHeightAndMinimumRatio(supportedSizes, requestedSize, previewAspectRatio, ratioMinDelta, width, height);
            ratioMinDelta = ratioMinDelta + 0.2;
        }
        Log.d(TAG, "RETURNED  SIZE width: " + optimalSize.width + " height: " + optimalSize.height);
        return optimalSize;
    }

    private Camera.Size findOptimalSizeByClosestHeightAndMinimumRatio(final List<Camera.Size> supportedSizes, Camera.Size requestedSize, double previewAspectRatio, double ratioMinDelta, int maxWidth, int maxHeight) {
        Camera.Size optimalSize = null;
        double minHeightDelta = Double.MAX_VALUE;
        for (Camera.Size size : supportedSizes) {
            Log.d(TAG, "ITERATED supported optimalSize width: " + size.width + " height: " + size.height);
            // Perfect match
            if (size.equals(requestedSize)) {
                Log.d(TAG, "GOT EXACT SIZE return ");
                return size;
            }
            if (size.height == 0) {
                continue;
            }
            double iteratedRatio = (double) size.width / size.height;
            Log.d(TAG, "iteratedRatio " + String.format("%.02f", iteratedRatio));
            double ratioDifference = Math.abs(previewAspectRatio - iteratedRatio);
            Log.d(TAG, "ratioDifference " + String.format("%.02f", ratioDifference) + " targetHeight " + requestedSize.height);
            if (ratioDifference <= ratioMinDelta && checkIfSizeLessThanMaximum(size, maxWidth, maxHeight)) {
                double heightDelta = Math.abs(requestedSize.height - size.height);
                Log.d(TAG, "heightDelta " + String.format("%.02f", heightDelta));
                boolean isHeightCloser = heightDelta < minHeightDelta;
                Log.d(TAG, "isHeightCloser " + isHeightCloser);
                if (isHeightCloser) {
                    optimalSize = size;
                    minHeightDelta = heightDelta;
                    Log.d(TAG, "NEW Actual minHeightDelta " + String.format("%.02f", minHeightDelta));
                    Log.d(TAG, "NEW Actual optimalSize width: " + optimalSize.width + " height: " + optimalSize.height);
                }
            } else {
                Log.d(TAG, "ratioDifference too high or picture too big");
            }
        }
        return optimalSize;
    }


    private boolean checkIfSizeLessThanMaximum(Camera.Size size, int maxWidth, int maxHeight) {
        return size.width * size.height <= maxWidth * maxHeight;
    }

    static byte[] rotateNV21(final byte[] yuv,
                             final int width,
                             final int height,
                             final int rotation) {
        if (rotation == 0) return yuv;
        if (rotation % 90 != 0 || rotation < 0 || rotation > 270) {
            throw new IllegalArgumentException("0 <= rotation < 360, rotation % 90 == 0");
        }

        final byte[] output = new byte[yuv.length];
        final int frameSize = width * height;
        final boolean swap = rotation % 180 != 0;
        final boolean xflip = rotation % 270 != 0;
        final boolean yflip = rotation >= 180;

        for (int j = 0; j < height; j++) {
            for (int i = 0; i < width; i++) {
                final int yIn = j * width + i;
                final int uIn = frameSize + (j >> 1) * width + (i & ~1);
                final int vIn = uIn + 1;

                final int wOut = swap ? height : width;
                final int hOut = swap ? width : height;
                final int iSwapped = swap ? j : i;
                final int jSwapped = swap ? i : j;
                final int iOut = xflip ? wOut - iSwapped - 1 : iSwapped;
                final int jOut = yflip ? hOut - jSwapped - 1 : jSwapped;

                final int yOut = jOut * wOut + iOut;
                final int uOut = frameSize + (jOut >> 1) * wOut + (iOut & ~1);
                final int vOut = uOut + 1;

                output[yOut] = (byte) (0xff & yuv[yIn]);
                output[uOut] = (byte) (0xff & yuv[uIn]);
                output[vOut] = (byte) (0xff & yuv[vIn]);
            }
        }
        return output;
    }

    public void takeSnapshot(final int quality) {
        mCamera.setPreviewCallback(new Camera.PreviewCallback() {
            @Override
            public void onPreviewFrame(byte[] bytes, Camera camera) {
                try {
                    Camera.Parameters parameters = camera.getParameters();
                    Camera.Size size = parameters.getPreviewSize();
                    int orientation = mPreview.getDisplayOrientation();
                    if (mPreview.getCameraFacing() == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                        bytes = rotateNV21(bytes, size.width, size.height, (360 - orientation) % 360);
                    } else {
                        bytes = rotateNV21(bytes, size.width, size.height, orientation);
                    }
                    // switch width/height when rotating 90/270 deg
                    Rect rect = orientation == 90 || orientation == 270 ?
                            new Rect(0, 0, size.height, size.width) :
                            new Rect(0, 0, size.width, size.height);
                    YuvImage yuvImage = new YuvImage(bytes, parameters.getPreviewFormat(), rect.width(), rect.height(), null);
                    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                    yuvImage.compressToJpeg(rect, quality, byteArrayOutputStream);
                    byte[] data = byteArrayOutputStream.toByteArray();
                    byteArrayOutputStream.close();
                    eventListener.onSnapshotTaken(Base64.encodeToString(data, Base64.NO_WRAP));
                } catch (IOException e) {
                    Log.e(TAG, "CameraPreview IOException", e);
                    eventListener.onSnapshotTakenError("IO Error");
                } finally {
                    mCamera.setPreviewCallback(null);
                }
            }
        });
    }

    public void takePicture(final int width, int height, final int quality) {
        Log.d(TAG, "CameraPreview takePicture width: " + width + ", height: " + height + ", quality: " + quality);

        cameraCurrentlyLocked = currentCameraId;

        if (mPreview != null) {
            if (!canTakePicture) {
                return;
            }

            canTakePicture = false;

            mCamera = Camera.open(cameraCurrentlyLocked);
            Camera.Parameters params = mCamera.getParameters();

            Camera.Size size =
                    getOptimalPictureSize(width, height, params.getPreviewSize(), params.getSupportedPictureSizes());
            Log.d(TAG, "TAKE PICTURE SIZE width: " + size.width + ", size.height: " + size.height);
            params.setPictureSize(size.width, size.height);
            currentQuality = quality;

            if (cameraCurrentlyLocked == Camera.CameraInfo.CAMERA_FACING_FRONT && !storeToFile) {
                // The image will be recompressed in the callback
                params.setJpegQuality(99);
            } else {
                params.setJpegQuality(quality);
            }

            params.setRotation(mPreview.getDisplayOrientation());
            mCamera.setDisplayOrientation(mPreview.displayOrientation);
            mCamera.setParameters(params);
            try {
                mCamera.setPreviewDisplay(mPreview.mHolder);
            } catch (IOException e) {
                Log.e(TAG, "Failed to set a preview display", e);
            }
            mCamera.startPreview();
            mPreview.postDelayed(() -> {
                mCamera.takePicture(shutterCallback, null, jpegPictureCallback);
            }, 300L);

        } else {
            canTakePicture = true;
        }
    }

    public void setFocusArea(final int pointX, final int pointY, final Camera.AutoFocusCallback callback) {
        if (mCamera != null) {
            try {
                Camera.Parameters parameters = mCamera.getParameters();

                Rect focusRect = calculateTapArea(pointX, pointY, 1f);
                parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
                parameters.setFocusAreas(Arrays.asList(new Camera.Area(focusRect, 1000)));

                if (parameters.getMaxNumMeteringAreas() > 0) {
                    Rect meteringRect = calculateTapArea(pointX, pointY, 1.5f);
                    parameters.setMeteringAreas(Arrays.asList(new Camera.Area(meteringRect, 1000)));
                }

                setCameraParameters(parameters);
                doAutoFocus(callback);
            } catch (Exception e) {
                Log.e(TAG, "Failed to set focus area", e);
                callback.onAutoFocus(false, mCamera);
            }
        }
    }

    private void doAutoFocus(@Nullable Camera.AutoFocusCallback callback) {
        new Handler()
                .postDelayed(() -> mCamera.autoFocus(callback), 200L);
    }

    private Rect calculateTapArea(float x, float y, float coefficient) {
        if (x < 100) {
            x = 100;
        }
        if (x > width - 100) {
            x = width - 100;
        }
        if (y < 100) {
            y = 100;
        }
        if (y > height - 100) {
            y = height - 100;
        }
        return new Rect(
                Math.round((x - 100) * 2000 / width - 1000),
                Math.round((y - 100) * 2000 / height - 1000),
                Math.round((x + 100) * 2000 / width - 1000),
                Math.round((y + 100) * 2000 / height - 1000)
        );
    }

    static class SavePhotoTask extends AsyncTask<SavePhotoTask.Args, String, String> {

        private final WeakReference<IPhotoPathProducer> photoPathProducer;

        SavePhotoTask(WeakReference<IPhotoPathProducer> photoPathProducer) {
            this.photoPathProducer = photoPathProducer;
        }

        private File preparePhotoFile(File cacheDirFile) {
            String generatedName = "/cpcp_capture_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8) + ".jpg";
            return new File(cacheDirFile, generatedName);
        }

        @Override
        protected String doInBackground(Args... args) {
            Args arg = args[0];
            if (arg.storeToFile) {
                return storeToFile(arg);
            } else {
                return Base64.encodeToString(arg.photoToWrite, Base64.NO_WRAP);
            }
        }

        private String storeToFile(Args args) {
            File photo = preparePhotoFile(args.cacheDir);

            if (photo.exists()) {
                photo.delete();
            }

            try {
                FileOutputStream fos = new FileOutputStream(photo.getPath());

                fos.write(args.photoToWrite);
                // fos.flush();
                fos.close();
            } catch (java.io.IOException e) {
                Log.e("PictureDemo", "Exception in photoCallback", e);
            }

            return photo.getAbsolutePath();
        }

        @Override
        protected void onPostExecute(String s) {
            super.onPostExecute(s);
            IPhotoPathProducer iPhotoPathProducer = photoPathProducer.get();
            if (iPhotoPathProducer != null) {
                iPhotoPathProducer.onPhotoResult(s);
            }
        }

        interface IPhotoPathProducer {
            void onPhotoResult(String path);
        }

        static class Args {
            private boolean storeToFile;
            private byte[] photoToWrite;
            private File cacheDir;

            Args(byte[] photoToWrite, File cacheDir, boolean storeToFile) {
                this.photoToWrite = photoToWrite;
                this.cacheDir = cacheDir;
                this.storeToFile = storeToFile;
            }
        }

    }
}
