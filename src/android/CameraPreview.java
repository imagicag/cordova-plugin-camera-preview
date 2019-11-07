package com.cordovaplugincamerapreview;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Rect;
import android.hardware.Camera;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.Window;

import com.bitpay.cordova.qrscanner.CameraUtils;
import com.bitpay.cordova.qrscanner.CommonData;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

public class CameraPreview extends CordovaPlugin implements CameraUtils.CameraPreviewListener {

    private static final String TAG = "CameraPreviewPlugin";

    private static final String COLOR_EFFECT_ACTION = "setColorEffect";
    private static final String SUPPORTED_COLOR_EFFECTS_ACTION = "getSupportedColorEffects";
    private static final String ZOOM_ACTION = "setZoom";
    private static final String GET_ZOOM_ACTION = "getZoom";
    private static final String GET_HFOV_ACTION = "getHorizontalFOV";
    private static final String GET_MAX_ZOOM_ACTION = "getMaxZoom";
    private static final String SUPPORTED_FLASH_MODES_ACTION = "getSupportedFlashModes";
    private static final String GET_FLASH_MODE_ACTION = "getFlashMode";
    private static final String SET_FLASH_MODE_ACTION = "setFlashMode";
    private static final String START_CAMERA_ACTION = "startCamera";
    private static final String STOP_CAMERA_ACTION = "stopCamera";
    private static final String PREVIEW_SIZE_ACTION = "setPreviewSize";
    private static final String SWITCH_CAMERA_ACTION = "switchCamera";
    private static final String TAKE_PICTURE_ACTION = "takePicture";
    private static final String TAKE_SNAPSHOT_ACTION = "takeSnapshot";
    private static final String SHOW_CAMERA_ACTION = "showCamera";
    private static final String HIDE_CAMERA_ACTION = "hideCamera";
    private static final String TAP_TO_FOCUS = "tapToFocus";
    private static final String SUPPORTED_PICTURE_SIZES_ACTION = "getSupportedPictureSizes";
    private static final String SUPPORTED_FOCUS_MODES_ACTION = "getSupportedFocusModes";
    private static final String SUPPORTED_WHITE_BALANCE_MODES_ACTION = "getSupportedWhiteBalanceModes";
    private static final String GET_FOCUS_MODE_ACTION = "getFocusMode";
    private static final String SET_FOCUS_MODE_ACTION = "setFocusMode";
    private static final String GET_EXPOSURE_MODES_ACTION = "getExposureModes";
    private static final String GET_EXPOSURE_MODE_ACTION = "getExposureMode";
    private static final String SET_EXPOSURE_MODE_ACTION = "setExposureMode";
    private static final String GET_EXPOSURE_COMPENSATION_ACTION = "getExposureCompensation";
    private static final String SET_EXPOSURE_COMPENSATION_ACTION = "setExposureCompensation";
    private static final String GET_EXPOSURE_COMPENSATION_RANGE_ACTION = "getExposureCompensationRange";
    private static final String GET_WHITE_BALANCE_MODE_ACTION = "getWhiteBalanceMode";
    private static final String SET_WHITE_BALANCE_MODE_ACTION = "setWhiteBalanceMode";
    private static final String SET_BACK_BUTTON_CALLBACK = "onBackButton";
    private static final String GET_CAMERA_CHARACTERISTICS_ACTION = "getCameraCharacteristics";

    private static final int CAM_REQ_CODE = 0;

    private static final String[] permissions = {
            Manifest.permission.CAMERA
    };

    private CallbackContext takePictureCallbackContext;
    private CallbackContext takeSnapshotCallbackContext;
    private CallbackContext setFocusCallbackContext;
    private CallbackContext startCameraCallbackContext;
    private CallbackContext tapBackButtonContext = null;

    private CallbackContext execCallback;

    private ViewParent webViewParent;

    public static int PREVIEW_Y = 0;
    public static int PREVIEW_X = 0;

    private boolean isFirstSetFlashCall = true;

    public CameraPreview() {
        super();
    }

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
        if (START_CAMERA_ACTION.equals(action)) {
            startCameraCallbackContext = callbackContext;

            setPreviewSize(args);

            if (cordova.hasPermission(permissions[0])) {
                onCameraStarted();
                return true;
            } else {
                this.execCallback = callbackContext;
                cordova.requestPermissions(this, CAM_REQ_CODE, permissions);
                return true;
            }
        } else if (TAKE_PICTURE_ACTION.equals(action)) {
            return takePicture(args.getInt(0), args.getInt(1), args.getInt(2), callbackContext);
        } else if (TAKE_SNAPSHOT_ACTION.equals(action)) {
            return takeSnapshot(args.getInt(0), callbackContext);
        } else if (SUPPORTED_FLASH_MODES_ACTION.equals(action)) {
            return getSupportedFlashModes(callbackContext);
        } else if (SET_FLASH_MODE_ACTION.equals(action)) {
            return setFlashMode(args.getString(0), callbackContext);
        } else if (STOP_CAMERA_ACTION.equals(action)) {
            return stopCamera(callbackContext);
        } else if (SHOW_CAMERA_ACTION.equals(action)) {
            return showCamera(callbackContext);
        } else if (HIDE_CAMERA_ACTION.equals(action)) {
            return hideCamera(callbackContext);
        } else if (TAP_TO_FOCUS.equals(action)) {
            return tapToFocus(args.getInt(0), args.getInt(1), callbackContext);
        } else if (SWITCH_CAMERA_ACTION.equals(action)) {
            return switchCamera(callbackContext);
        } else if (SET_BACK_BUTTON_CALLBACK.equals(action)) {
            return setBackButtonListener(callbackContext);
        }
        return false;
    }

    private void setPreviewSize(JSONArray args) throws JSONException {
        final Activity activity = cordova.getActivity();
        Resources resources = activity.getResources();
        DisplayMetrics metrics = resources.getDisplayMetrics();

        int height = args.getInt(3);
        int computedHeight = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, height, metrics);

        Rect rectangle = new Rect();
        Window window = cordova.getActivity().getWindow();
        window.getDecorView().getWindowVisibleDisplayFrame(rectangle);

        int statusBarHeight = rectangle.top;
        int softButtonsBarHeight = getSoftButtonsBarHeight();

        computedHeight = computedHeight - statusBarHeight - softButtonsBarHeight;

        PREVIEW_X = rectangle.right;
        PREVIEW_Y = computedHeight;
    }

    @Override
    public void onRequestPermissionResult(int requestCode, String[] permissions, int[] grantResults) throws JSONException {
        for (int r : grantResults) {
            if (r == PackageManager.PERMISSION_DENIED) {
                execCallback.sendPluginResult(new PluginResult(PluginResult.Status.ILLEGAL_ACCESS_EXCEPTION));
                return;
            }
        }
        if (requestCode == CAM_REQ_CODE) {
            onCameraStarted();
        }
    }

    private boolean hasView(CallbackContext callbackContext) {
        return true;
    }

    @SuppressLint("NewApi")
    private int getSoftButtonsBarHeight() {
        // getRealMetrics is only available with API 17 and +
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            DisplayMetrics metrics = new DisplayMetrics();
            cordova.getActivity().getWindowManager().getDefaultDisplay().getMetrics(metrics);
            int usableHeight = metrics.heightPixels;
            cordova.getActivity().getWindowManager().getDefaultDisplay().getRealMetrics(metrics);
            int realHeight = metrics.heightPixels;
            if (realHeight > usableHeight)
                return realHeight - usableHeight;
            else
                return 0;
        }
        return 0;
    }

    public void onCameraStarted() {
        Log.d(TAG, "Camera started");

        PluginResult pluginResult = new PluginResult(PluginResult.Status.OK, "Camera started");
        pluginResult.setKeepCallback(true);
        startCameraCallbackContext.sendPluginResult(pluginResult);
    }

    private boolean takeSnapshot(int quality, CallbackContext callbackContext) {
        if (!this.hasView(callbackContext)) {
            return true;
        }

        takeSnapshotCallbackContext = callbackContext;
        return true;
    }

    public void onSnapshotTaken(String originalPicture) {
        Log.d(TAG, "returning snapshot");

        JSONArray data = new JSONArray();
        data.put(originalPicture);

        PluginResult pluginResult = new PluginResult(PluginResult.Status.OK, data);
        pluginResult.setKeepCallback(true);
        takeSnapshotCallbackContext.sendPluginResult(pluginResult);
        takeSnapshotCallbackContext = null;
    }

    public void onSnapshotTakenError(String message) {
        Log.d(TAG, "CameraPreview onSnapshotTakenError");
        takeSnapshotCallbackContext.error(message);
        takeSnapshotCallbackContext = null;
    }

    private boolean takePicture(int width, int height, int quality, CallbackContext callbackContext) {
        takePictureCallbackContext = callbackContext;
        if (!CommonData.getInstance().canTakePhoto) {
            takePictureCallbackContext.success("");
        } else {
            CameraUtils utils = new CameraUtils(cordova.getActivity());
            utils.setEventListener(this);
            utils.takePicture(width, height, quality);
        }
        return true;
    }


    public void onPictureTaken(String originalPicture) {
        Log.d(TAG, "returning picture");
        CommonData.getInstance().getScannerModule().execute("scan", new JSONArray(), CommonData.getInstance().getScanCallback());

    }

    @Override
    public void onDataReady(String data) {
        PluginResult pluginResult = new PluginResult(PluginResult.Status.OK, data);
        pluginResult.setKeepCallback(true);
//        takePictureCallbackContext.success("");
        takePictureCallbackContext.sendPluginResult(pluginResult);
    }

    public void onPictureTakenError(String message) {
        Log.d(TAG, "CameraPreview onPictureTakenError, message: " + message);
        takePictureCallbackContext.error(message);
    }


    private boolean getSupportedFlashModes(CallbackContext callbackContext) {
        Camera camera = CommonData.getInstance().getCamera();
        if (camera == null) {
            return true;
        }

        Camera.Parameters params = camera.getParameters();
        List<String> supportedFlashModes;
        supportedFlashModes = params.getSupportedFlashModes();

        JSONArray jsonFlashModes = new JSONArray();

        if (supportedFlashModes != null) {
            for (int i = 0; i < supportedFlashModes.size(); i++) {
                if ("auto, off, on".contains(supportedFlashModes.get(i))) {
                    jsonFlashModes.put(supportedFlashModes.get(i));
                }
            }
        }

        callbackContext.success(jsonFlashModes);
        return true;
    }


    private boolean setFlashMode(String flashMode, CallbackContext callbackContext) {
        Log.d(TAG, "setFlashMode " + flashMode);
        CommonData.getInstance().flashMode = flashMode;
        CommonData.getInstance().getScannerModule().switchCamera(null, null);
        callbackContext.success(flashMode);
        return true;
    }

    private boolean stopCamera(CallbackContext callbackContext) {
        if (webViewParent != null) {
            cordova.getActivity().runOnUiThread(() -> {
                ViewGroup view = (ViewGroup) webView.getView();
                view.bringToFront();
                view.setBackgroundColor(Color.WHITE);
                webViewParent = null;
            });
        }

        if (!hasView(callbackContext)) {
            return true;
        }


        callbackContext.success();
        return true;
    }

    private boolean showCamera(CallbackContext callbackContext) {
        if (!this.hasView(callbackContext)) {
            return true;
        }

        callbackContext.success();
        return true;
    }

    private boolean hideCamera(CallbackContext callbackContext) {
        if (!this.hasView(callbackContext)) {
            return true;
        }


        callbackContext.success();
        return true;
    }

    private boolean tapToFocus(final int pointX, final int pointY, CallbackContext callbackContext) {
        if (!this.hasView(callbackContext)) {
            return true;
        }

        setFocusCallbackContext = callbackContext;

        return true;
    }

    public void onFocusSet(final int pointX, final int pointY) {
        Log.d(TAG, "Focus set, returning coordinates");

        JSONObject data = new JSONObject();
        try {
            data.put("x", pointX);
            data.put("y", pointY);
        } catch (JSONException e) {
            Log.d(TAG, "onFocusSet failed to set output payload");
        }

        PluginResult pluginResult = new PluginResult(PluginResult.Status.OK, data);
        pluginResult.setKeepCallback(true);
        setFocusCallbackContext.sendPluginResult(pluginResult);
    }

    public void onFocusSetError(String message) {
        Log.d(TAG, "CameraPreview onFocusSetError");
        setFocusCallbackContext.error(message);
    }

    private boolean switchCamera(CallbackContext callbackContext) {
        if (!this.hasView(callbackContext)) {
            return true;
        }

        callbackContext.success();
        return true;
    }

    public boolean setBackButtonListener(CallbackContext callbackContext) {
        tapBackButtonContext = callbackContext;
        return true;
    }

    public void onBackButton() {
        if (tapBackButtonContext == null) {
            return;
        }
        Log.d(TAG, "Back button tapped, notifying");
        PluginResult pluginResult = new PluginResult(PluginResult.Status.OK, "Back button pressed");
        tapBackButtonContext.sendPluginResult(pluginResult);
    }

}
