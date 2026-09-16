package org.libsdl.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.view.InputDevice;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RelativeLayout;

import org.ikemen_engine.ikemen_go.R;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.graphics.Color;

public class ControllerOverlay extends RelativeLayout {
    private int virtualDeviceId;
    private int hatX, hatY = 0;
    private final Map<Integer, Set<Integer>> pointerStates = new HashMap<>(); // PointerID -> Keycode/Axis
    private boolean isInitialized = false;

    // ---------------- Touch Layout Editor (edit mode) ----------------
    private boolean editMode = false;
    private View editToolbar = null;
    private View editSelectedView = null;
    private int editDragPointerId = -1;
    private float editDragDX = 0f, editDragDY = 0f;
    private Handler editTriggerHandler = null;
    private Runnable editTriggerChecker = null;
    private static final String EDIT_TRIGGER_FILE = "touch_edit.trigger";
    // Button view IDs that can be repositioned in edit mode
    private static final int[] EDITABLE_VIEW_IDS = {
        R.id.left_analog, R.id.right_analog,
        R.id.btn_a, R.id.btn_b, R.id.btn_c,
        R.id.btn_x, R.id.btn_y, R.id.btn_z,
        R.id.btn_d, R.id.btn_w,
        R.id.btn_start, R.id.btn_back,
    };

    public int getPhysicalJoystickCount() {
        int count = 0;
        int[] deviceIds = InputDevice.getDeviceIds();
        for (int id : deviceIds) {
            InputDevice dev = InputDevice.getDevice(id);
            // Check if the device is a physical joystick or gamepad
            // OLD LOGIC: Checked only flags (Let sensors through)
            // NEW LOGIC: Checks isRealGamepad (Stricter filtering by querying for buttons)
            if (isRealGamepad(dev)) {
                count++;
            }
        }
        return count;
    }

    // Helper to distinguish real controllers from "Ghost" sensors
    private boolean isRealGamepad(InputDevice device) {
        if (device == null) return false;

        int sources = device.getSources();
        boolean isJoystick = ((sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) ||
                ((sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD);

        // Must identify as joystick/gamepad
        if (!isJoystick) return false;

        // Ignore virtual devices (optional, prevents software loopbacks)
        if (device.isVirtual()) return false;

        // Ghost filter, does it have actual buttons?
        // Sensors report as Joysticks but have NO buttons.
        // Real gamepads always have at least one of these.
        int[] keysToCheck = {
                android.view.KeyEvent.KEYCODE_BUTTON_A,
                android.view.KeyEvent.KEYCODE_BUTTON_B,
                android.view.KeyEvent.KEYCODE_BUTTON_X,
                android.view.KeyEvent.KEYCODE_BUTTON_Y,
                android.view.KeyEvent.KEYCODE_BUTTON_START,
                android.view.KeyEvent.KEYCODE_BUTTON_SELECT,
        };

        boolean[] hasKeys = device.hasKeys(keysToCheck);
        for (boolean exists : hasKeys) {
            if (exists) return true; // It has any of these buttons, it's a real gamepad
        }

        return false; // No buttons, its a sensor, ignore it
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (!isInitialized) {
            initializeVirtualController();
            isInitialized = true;
        }
        return true;
    }

    private int leftJoyPointerId = -1;
    private int rightJoyPointerId = -1;

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (editMode) {
            return onEditTouchEvent(event);
        }
        int action = event.getActionMasked();
        int index = event.getActionIndex();
        int pId = event.getPointerId(index);

        // Get raw coordinates for global hit detection
        float x = event.getX(index);
        float y = event.getY(index);

        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            // Check Joysticks first (circular capture with configurable leniency)
            if (isStickAt(findViewById(R.id.left_analog), x, y)) {
                leftJoyPointerId = pId;
            } else if (isStickAt(findViewById(R.id.right_analog), x, y)) {
                rightJoyPointerId = pId;
            } else {
                // If not a stick, it's a button/dpad
                updatePointer(pId, getButtonsAt(x, y));
            }
        } else if (action == MotionEvent.ACTION_MOVE) {
            for (int i = 0; i < event.getPointerCount(); i++) {
                int movePId = event.getPointerId(i); // The stable ID

                // Get coordinates specifically for THIS pointer index
                float mx = event.getX(i);
                float my = event.getY(i);

                if (movePId == leftJoyPointerId) {
                    updateJoystickLogic(findViewById(R.id.left_analog), mx, my);
                } else if (movePId == rightJoyPointerId) {
                    updateJoystickLogic(findViewById(R.id.right_analog), mx, my);
                } else {
                    Set<Integer> currentButtons = getButtonsAt(mx, my);
                    Set<Integer> lastButtons = pointerStates.get(movePId);

                    // If the sets are different, update the physical state
                    if (!currentButtons.equals(lastButtons)) {
                        updatePointer(movePId, currentButtons);
                    }
                }
            }
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_CANCEL) {
            if (pId == leftJoyPointerId) {
                resetJoystick(findViewById(R.id.left_analog));
                leftJoyPointerId = -1;
            } else if (pId == rightJoyPointerId) {
                resetJoystick(findViewById(R.id.right_analog));
                rightJoyPointerId = -1;
            } else {
                releasePointer(pId);
            }
        }

        // D-Pad removed. Left analog stick is the only movement input.
        return true;
    }

    private void updateJoystickLogic(View v, float x, float y) {
        if (!(v instanceof JoystickOverlay)) return;
        JoystickOverlay joy = (JoystickOverlay) v;

        // LOCAL CENTER CALCULATION
        float centerX = v.getLeft() + (v.getWidth() / 2f);
        float centerY = v.getTop() + (v.getHeight() / 2f);
        float radius = v.getWidth() / 3f;

        float dx = x - centerX;
        float dy = y - centerY;
        float distance = (float) Math.sqrt(dx * dx + dy * dy);

        if (distance > radius) {
            dx = (dx / distance) * radius;
            dy = (dy / distance) * radius;
        }

        joy.updateVisualPos((v.getWidth()/2f) + dx, (v.getHeight()/2f) + dy);
        joy.sendToSDL(dx / radius, dy / radius);
    }

    private void resetJoystick(View v) {
        if (!(v instanceof JoystickOverlay)) return;
        JoystickOverlay joy = (JoystickOverlay) v;
        joy.updateVisualPos(v.getWidth()/2f, v.getHeight()/2f);
        joy.sendToSDL(0, 0);
    }

    private void updatePointer(int pointerId, Set<Integer> newCodes) {
        Set<Integer> oldCodes = pointerStates.get(pointerId);
        if (oldCodes == null) oldCodes = new HashSet<>();
        // Find buttons that were RELEASED (in old set, not in new)
        for (Integer oldCode : oldCodes) {
            if (!newCodes.contains(oldCode)) {
                handleInput(oldCode, false);
            }
        }
        // Find buttons that were PRESSED (in new set, not in old)
        for (Integer newCode : newCodes) {
            if (!oldCodes.contains(newCode)) {
                handleInput(newCode, true);
            }
        }
        // Save the new state for this finger
        pointerStates.put(pointerId, newCodes);
    }

    private void releasePointer(int pointerId) {
        Set<Integer> lastCodes = pointerStates.remove(pointerId);
        if (lastCodes != null) {
            for (Integer code : lastCodes) {
                handleInput(code, false);
            }
        }
    }

    private void handleInput(final int code, boolean pressed) {
        if (code == -1) return;

        if (code >= 4000) { // TRIGGERS (W/C): analog axis events only.
            // The engine's joystick key-remap polls axes (getJoystickKey),
            // so triggers must arrive as axis values. Press -> 1.0,
            // release -> 0.0. No keyboard fallback for triggers: the earlier
            // freeze happened when BOTH paths fired while remapping.
            int axis = code - 4000;
            SDLControllerManager.onNativeJoy(virtualDeviceId, axis, pressed ? 1.0f : 0.0f);
        }
        else if (code >= 1000) {
            // D-Pad removed; these codes are never produced.
        }
        else { // STANDARD BUTTONS
            if (pressed) {
                int result = SDLControllerManager.onNativePadDown(virtualDeviceId, code);
                if (result < 0) {
                    android.util.Log.e("SDL", "INPUT FAILURE: Device " + virtualDeviceId + " rejected button " + code);
                }
            } else {
                SDLControllerManager.onNativePadUp(virtualDeviceId, code);
            }
        }

        // Keyboard fallback (duplicate-safe: engine ignores repeated press).
        // Not applied to triggers (>=4000): remap screen polls axes only and
        // a simultaneous fake keyboard press froze that path.
        int key = code >= 4000 ? -1 : keyFallbackFor(code);
        if (key != -1) {
            if (pressed) SDLActivity.onNativeKeyDown(key);
            else SDLActivity.onNativeKeyUp(key);
        }

        // Always update visuals
        updateButtonVisual(code, pressed);
    }

    // Fallback keyboard mapping so touch buttons always register in the
    // engine even when SDL never opens the virtual gamepad as a controller.
    // Codes follow the engine's default keyboard layout (Keys_P1).
    private int keyFallbackFor(int code) {
        switch (code) {
            case 96:  return 54; // BUTTON_A  -> engine 'a' = z
            case 97:  return 52; // BUTTON_B  -> engine 'b' = x
            case 99:  return 29; // BUTTON_X  -> engine 'x' = a
            case 100: return 47; // BUTTON_Y  -> engine 'y' = s
            case 102: return 45; // BUTTON_D/LB -> engine 'd' = q
            case 103: return 32; // BUTTON_Z/RB -> engine 'z' = d
            case 108: return 66; // START     -> RETURN
            default:  return -1;
        }
    }

    private void updateButtonVisual(int code, boolean pressed) {
        int viewId = -1;
        switch (code) {
            case 96:  viewId = R.id.btn_a; break;
            case 97:  viewId = R.id.btn_b; break;
            case 99:  viewId = R.id.btn_x; break;
            case 100: viewId = R.id.btn_y; break;
            case 102: viewId = R.id.btn_d; break;
            case 103: viewId = R.id.btn_z; break;
            case 108: viewId = R.id.btn_start; break;
            case 109: viewId = R.id.btn_back; break;
            case 4004: viewId = R.id.btn_w; break;
            case 4005: viewId = R.id.btn_c; break;
        }

        if (viewId != -1) {
            View v = findViewById(viewId);
            if (v != null) v.setPressed(pressed);
        }
    }


    private int dpToPx(int dp) {
        float density = getContext().getResources().getDisplayMetrics().density;
        return Math.round((float) dp * density);
    }

    private Set<Integer> getButtonsAt(float x, float y) {
        Set<Integer> detectedButtons = new HashSet<>();

        // Define a "padding" factor in pixels. This allows the thumb to trigger
        // two buttons if it's in the gap between them.
        int padding = dpToPx(16);

        checkAndAdd(detectedButtons, R.id.btn_a, 96, x, y, padding);
        checkAndAdd(detectedButtons, R.id.btn_b, 97, x, y, padding);
        checkAndAdd(detectedButtons, R.id.btn_x, 99, x, y, padding);
        checkAndAdd(detectedButtons, R.id.btn_y, 100, x, y, padding);
        checkAndAdd(detectedButtons, R.id.btn_d, 102, x,y, padding);
        checkAndAdd(detectedButtons, R.id.btn_z, 103, x,y, padding);
        checkAndAdd(detectedButtons, R.id.btn_w, 4004, x,y, padding);
        checkAndAdd(detectedButtons, R.id.btn_c, 4005, x,y, padding);
        checkAndAdd(detectedButtons, R.id.btn_start, 108, x,y, padding);
        checkAndAdd(detectedButtons, R.id.btn_back, 109, x,y, padding);

        return detectedButtons;
    }

    private void checkAndAdd(Set<Integer> set, int viewId, int code, float x, float y, int pad) {
        View v = findViewById(viewId);
        if (v == null) return;

        int[] loc = new int[2];
        int[] parentLoc = new int[2];
        v.getLocationOnScreen(loc);
        this.getLocationOnScreen(parentLoc);

        // Calculate coordinates with padding
        int left   = loc[0] - parentLoc[0] - pad;
        int top    = loc[1] - parentLoc[1] - pad;
        int right  = left + v.getWidth() + pad*2;
        int bottom = top + v.getHeight() + pad*2;

        // Add if in region + padding
        if (x >= left && x <= right && y >= top && y <= bottom) {
            set.add(code);
        }
    }


    // Circular capture test for the analog sticks: true when the finger
    // starts within the stick base radius (with grab leniency) so the thumb
    // doesn't need to land exactly on the base center.
    private boolean isStickAt(View v, float x, float y) {
        if (v == null || v.getVisibility() != View.VISIBLE) return false;

        int[] vLoc = new int[2];
        v.getLocationOnScreen(vLoc); // Global position of button

        int[] parentLoc = new int[2];
        this.getLocationOnScreen(parentLoc); // Global position of the overlay

        float cx = (vLoc[0] - parentLoc[0]) + v.getWidth() / 2f;
        float cy = (vLoc[1] - parentLoc[1]) + v.getHeight() / 2f;
        float radius = Math.min(v.getWidth(), v.getHeight()) / 2f;

        float leniency = 1.75f;
        if (v instanceof JoystickOverlay) leniency = ((JoystickOverlay) v).getGrabLeniency();

        float dx = x - cx, dy = y - cy;
        return (dx * dx + dy * dy) <= (radius * leniency) * (radius * leniency);
    }

    // Helper to check if a finger is inside a specific button's area
    private boolean isViewAtLocation(View v, float x, float y) {
        if (v == null || v.getVisibility() != View.VISIBLE) return false;

        int[] vLoc = new int[2];
        v.getLocationOnScreen(vLoc); // Global position of button

        int[] parentLoc = new int[2];
        this.getLocationOnScreen(parentLoc); // Global position of the overlay

        // Get the position of the button RELATIVE to the overlay
        int relativeLeft = vLoc[0] - parentLoc[0];
        int relativeTop = vLoc[1] - parentLoc[1];

        return x >= relativeLeft && x <= (relativeLeft + v.getWidth()) &&
                y >= relativeTop && y <= (relativeTop + v.getHeight());
    }

    public ControllerOverlay(Context context) {
        super(context);
        this.setClickable(true);
        this.setFocusable(true);
        this.setEnabled(true);
        this.setMotionEventSplittingEnabled(true);

        // Inflate the buttons
        LayoutInflater inflater = LayoutInflater.from(context);
        View vc = inflater.inflate(R.layout.virtual_controller, this, true);
        disableAllTouches(vc);
    }

    private void disableAllTouches(View v) {
        v.setClickable(false);
        v.setFocusable(false);
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                disableAllTouches(vg.getChildAt(i));
            }
        }
    }

    public void initializeVirtualController() {
        android.util.Log.i("ControllerOverlay", "DEBUG: initializing virtual joystick...");
        virtualDeviceId = getPhysicalJoystickCount();
        pointerStates.clear();
        hatX = 0;
        hatY = 0;
        android.util.Log.i("ControllerOverlay", String.format("DEBUG: virtualDeviceID = %d", virtualDeviceId));
        SDLControllerManager.nativeRemoveJoystick(virtualDeviceId);
        ensureJoystickAlive();
        applyStickConfig(findViewById(R.id.left_analog));
        applyStickConfig(findViewById(R.id.right_analog));
        // Initialize the joysticks
        JoystickOverlay ls = findViewById(R.id.left_analog);
        ls.setAttrs(virtualDeviceId, 0, 1);

        JoystickOverlay rs = findViewById(R.id.right_analog);
        rs.setAttrs(virtualDeviceId, 2, 3);

        for (int i = 0; i < 6; i++) {
            SDLControllerManager.onNativeJoy(virtualDeviceId, i, 0.00390625f);
            SDLControllerManager.onNativeJoy(virtualDeviceId, i, 0.0f);
        }
        SDLControllerManager.onNativeHat(virtualDeviceId, 0, 0, 0);
        android.util.Log.i("ControllerOverlay", "DEBUG: virtual joystick initialized!");
        isInitialized = true;
        // Apply any user-customized touch layout (button positions/sizes/alpha)
        applyEditLayout();
    }

    private int ensureJoystickAlive() {
        return SDLControllerManager.nativeAddJoystick(virtualDeviceId, "Xbox 360 Controller", "Gamepad", 0x045E, 0x028E, false, 0xFFF, 6, 0x3F, 1, 0);
    }

    // --------------- Analog stick configuration ---------------
    //
    // The left analog stick (movement) replaces the old D-Pad and is fully
    // configurable at runtime. Values are resolved in this order:
    //   1. <files-dir>/virtual_controller.ini  (user-editable INI)
    //   2. SharedPreferences ("virtual_controller")
    //   3. Built-in defaults (God-of-War style large left stick)
    //
    // INI example (Android/data/org.ikemen_engine.ikemen_go/files/virtual_controller.ini):
    //   stick_size         = 190   ; base diameter in dp (90..300)
    //   stick_x            = 55    ; left margin in dp
    //   stick_y            = 5     ; bottom margin in dp
    //   deadzone           = 0.15  ; fraction of full deflection (0..0.5)
    //   grab_leniency      = 1.75  ; capture radius multiplier around the base
    private void applyStickConfig(View v) {
        if (v == null) return;
        Context ctx = getContext();
        float sizeDp = 170f, xDp = 40f, yDp = 55f, deadzone = 0.15f, grab = 1.75f;

        // 2) SharedPreferences
        SharedPreferences sp = ctx.getSharedPreferences("virtual_controller",
                Context.MODE_PRIVATE);
        sizeDp = sp.getFloat("stick_size", sizeDp);
        xDp = sp.getFloat("stick_x", xDp);
        yDp = sp.getFloat("stick_y", yDp);
        deadzone = sp.getFloat("deadzone", deadzone);
        grab = sp.getFloat("grab_leniency", grab);

        // 1) INI file overrides
        try {
            File ini = new File(ctx.getExternalFilesDir(null), "virtual_controller.ini");
            if (ini.isFile()) {
                InputStream in = new FileInputStream(ini);
                Properties pr = new Properties();
                pr.load(in);
                in.close();
                sizeDp = parse(pr.getProperty("stick_size"), sizeDp);
                xDp = parse(pr.getProperty("stick_x"), xDp);
                yDp = parse(pr.getProperty("stick_y"), yDp);
                deadzone = parse(pr.getProperty("deadzone"), deadzone);
                grab = parse(pr.getProperty("grab_leniency"), grab);
            }
        } catch (Exception e) {
            Log.w("ControllerOverlay", "stick config not read: " + e);
        }

        // Clamp to sane ranges; deadzone/grab are per-view semantics handled below
        sizeDp = clamp(sizeDp, 90f, 300f);
        deadzone = clamp(deadzone, 0f, 0.5f);
        grab = clamp(grab, 1f, 3f);

        RelativeLayout.LayoutParams lp = (RelativeLayout.LayoutParams) v.getLayoutParams();
        float density = ctx.getResources().getDisplayMetrics().density;
        lp.width = Math.round(sizeDp * density);
        lp.height = lp.width;
        lp.setMargins(Math.round(xDp * density), 0, 0, Math.round(yDp * density));
        v.setLayoutParams(lp);

        // Pass stick tuning to the overlay view itself.
        if (v instanceof JoystickOverlay) {
            JoystickOverlay joy = (JoystickOverlay) v;
            joy.setDeadzone(deadzone);
            joy.setGrabLeniency(grab);
        }
        Log.i("ControllerOverlay", String.format(
                "stick config: size=%.1fdp x=%.1f y=%.1f deadzone=%.2f grab=%.2f",
                sizeDp, xDp, yDp, deadzone, grab));
    }

    private static float parse(String s, float dflt) {
        if (s == null) return dflt;
        try {
            return Float.parseFloat(s.trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    private static float clamp(float v, float min, float max) {
        return v < min ? min : (v > max ? max : v);
    }

    // ==================== Touch Layout Editor ====================

    /** Enter or exit the touch layout editor. Called from SDLActivity. */
    public void setEditMode(boolean enabled) {
        if (editMode == enabled) return;
        editMode = enabled;
        if (enabled) {
            editSelectedView = null;
            buildEditToolbar();
            // Make sure layout is loaded before editing
            Log.i("ControllerOverlay", "Touch editor: edit mode ON");
        } else {
            if (editToolbar != null) {
                removeView(editToolbar);
                editToolbar = null;
            }
            saveEditLayout();
            Log.i("ControllerOverlay", "Touch editor: edit mode OFF, layout saved");
        }
    }

    public boolean isEditMode() {
        return editMode;
    }

    /** Start watching for the Lua-triggered edit request file. */
    public void startEditTriggerWatch() {
        if (editTriggerHandler != null) return;
        editTriggerHandler = new Handler(Looper.getMainLooper());
        editTriggerChecker = new Runnable() {
            @Override
            public void run() {
                try {
                    File trigger = new File(getContext().getExternalFilesDir(null), EDIT_TRIGGER_FILE);
                    if (trigger.isFile()) {
                        trigger.delete();
                        post(new Runnable() {
                            @Override public void run() { setEditMode(true); }
                        });
                    }
                } catch (Exception e) {
                    Log.w("ControllerOverlay", "trigger watch: " + e);
                }
                if (editTriggerHandler != null) {
                    editTriggerHandler.postDelayed(editTriggerChecker, 800);
                }
            }
        };
        editTriggerHandler.post(editTriggerChecker);
    }

    public void stopEditTriggerWatch() {
        if (editTriggerHandler != null) {
            editTriggerHandler.removeCallbacks(editTriggerChecker);
            editTriggerHandler = null;
            editTriggerChecker = null;
        }
    }

    private boolean onEditTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        int index = event.getActionIndex();
        int pId = event.getPointerId(index);
        float x = event.getX(index);
        float y = event.getY(index);

        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            if (editDragPointerId == -1) {
                View v = findEditViewAt(x, y);
                if (v != null) {
                    editDragPointerId = pId;
                    editSelectedView = v;
                    editDragDX = v.getX() - x;
                    editDragDY = v.getY() - y;
                    v.bringToFront();
                    updateEditToolbarLabel();
                    return true;
                }
            }
        } else if (action == MotionEvent.ACTION_MOVE) {
            for (int i = 0; i < event.getPointerCount(); i++) {
                if (event.getPointerId(i) == editDragPointerId && editSelectedView != null) {
                    float nx = event.getX(i) + editDragDX;
                    float ny = event.getY(i) + editDragDY;
                    // Clamp inside parent
                    nx = Math.max(0, Math.min(nx, getWidth() - editSelectedView.getWidth()));
                    ny = Math.max(0, Math.min(ny, getHeight() - editSelectedView.getHeight()));
                    editSelectedView.setX(nx);
                    editSelectedView.setY(ny);
                    return true;
                }
            }
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP
                || action == MotionEvent.ACTION_CANCEL) {
            if (pId == editDragPointerId) {
                editDragPointerId = -1;
                return true;
            }
        }
        return true; // consume all touches in edit mode
    }

    private View findEditViewAt(float x, float y) {
        for (int id : EDITABLE_VIEW_IDS) {
            View v = findViewById(id);
            if (v == null || v.getVisibility() != View.VISIBLE) continue;
            float vx = v.getX(), vy = v.getY();
            if (x >= vx && x <= vx + v.getWidth() && y >= vy && y <= vy + v.getHeight()) {
                return v;
            }
        }
        return null;
    }

    private String editNameFor(View v) {
        int id = v.getId();
        if (id == R.id.left_analog) return "stick";
        if (id == R.id.right_analog) return "stick2";
        if (id == R.id.btn_a) return "a";
        if (id == R.id.btn_b) return "b";
        if (id == R.id.btn_c) return "c";
        if (id == R.id.btn_x) return "x";
        if (id == R.id.btn_y) return "y";
        if (id == R.id.btn_z) return "z";
        if (id == R.id.btn_d) return "d";
        if (id == R.id.btn_w) return "w";
        if (id == R.id.btn_start) return "start";
        if (id == R.id.btn_back) return "back";
        return null;
    }

    private TextView editLabel = null;
    private SeekBar editAlphaBar = null;

    private void buildEditToolbar() {
        Context ctx = getContext();
        float density = ctx.getResources().getDisplayMetrics().density;

        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(Color.argb(220, 20, 20, 30));
        int pad = Math.round(8 * density);
        bar.setPadding(pad, pad, pad, pad);

        editLabel = new TextView(ctx);
        editLabel.setText("Drag buttons");
        editLabel.setTextColor(Color.WHITE);
        editLabel.setPadding(pad, 0, pad, 0);
        bar.addView(editLabel);

        Button minus = new Button(ctx);
        minus.setText("−");
        minus.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { adjustEditSize(0.9f); }
        });
        bar.addView(minus);

        Button plus = new Button(ctx);
        plus.setText("+");
        plus.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { adjustEditSize(1.1f); }
        });
        bar.addView(plus);

        TextView alphaLbl = new TextView(ctx);
        alphaLbl.setText(" Fade");
        alphaLbl.setTextColor(Color.WHITE);
        bar.addView(alphaLbl);

        editAlphaBar = new SeekBar(ctx);
        editAlphaBar.setMax(100);
        SharedPreferences sp0 = ctx.getSharedPreferences("virtual_controller", Context.MODE_PRIVATE);
        editAlphaBar.setProgress(Math.round(sp0.getFloat("btn_alpha", 1f) * 100));
        LinearLayout.LayoutParams sbLp = new LinearLayout.LayoutParams(
                Math.round(120 * density), LinearLayout.LayoutParams.WRAP_CONTENT);
        editAlphaBar.setLayoutParams(sbLp);
        editAlphaBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                applyEditAlpha(p / 100f);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        bar.addView(editAlphaBar);

        Button reset = new Button(ctx);
        reset.setText("Reset");
        reset.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { resetEditLayout(); }
        });
        bar.addView(reset);

        Button done = new Button(ctx);
        done.setText("Done");
        done.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { setEditMode(false); }
        });
        bar.addView(done);

        RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT);
        lp.addRule(RelativeLayout.ALIGN_PARENT_TOP);
        bar.setLayoutParams(lp);

        addView(bar);
        editToolbar = bar;
        editToolbar.bringToFront();
    }

    private void updateEditToolbarLabel() {
        if (editLabel == null) return;
        if (editSelectedView != null) {
            String n = editNameFor(editSelectedView);
            editLabel.setText("Selected: " + (n != null ? n : "?"));
        } else {
            editLabel.setText("Drag buttons");
        }
    }

    private void adjustEditSize(float factor) {
        if (editSelectedView == null) return;
        View v = editSelectedView;
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        int nw = Math.round(lp.width * factor);
        int nh = Math.round(lp.height * factor);
        nw = Math.max(40, Math.min(nw, 600));
        nh = Math.max(40, Math.min(nh, 600));
        lp.width = nw;
        lp.height = nw; // keep square
        v.setLayoutParams(lp);
    }

    private void applyEditAlpha(float alpha) {
        alpha = Math.max(0.15f, Math.min(1f, alpha));
        for (int id : EDITABLE_VIEW_IDS) {
            View v = findViewById(id);
            if (v != null && v != editToolbar) v.setAlpha(alpha);
        }
        getContext().getSharedPreferences("virtual_controller", Context.MODE_PRIVATE)
                .edit().putFloat("btn_alpha", alpha).apply();
    }

    private void saveEditLayout() {
        Context ctx = getContext();
        float density = ctx.getResources().getDisplayMetrics().density;
        SharedPreferences.Editor ed = ctx.getSharedPreferences("virtual_controller",
                Context.MODE_PRIVATE).edit();
        for (int id : EDITABLE_VIEW_IDS) {
            View v = findViewById(id);
            if (v == null) continue;
            String n = editNameFor(v);
            if (n == null) continue;
            float xDp = v.getX() / density;
            float yDp = v.getY() / density;
            float sizeDp = v.getWidth() / density;
            if (n.equals("stick") || n.equals("stick2")) {
                // stick uses legacy keys: size + left/bottom margins
                float bottomMarginDp = (getHeight() - (v.getY() + v.getHeight())) / density;
                if (n.equals("stick")) {
                    ed.putFloat("stick_size", sizeDp);
                    ed.putFloat("stick_x", xDp);
                    ed.putFloat("stick_y", bottomMarginDp);
                }
            } else {
                ed.putFloat("btn_" + n + "_x", xDp);
                ed.putFloat("btn_" + n + "_y", yDp);
                ed.putFloat("btn_" + n + "_size", sizeDp);
            }
        }
        ed.apply();
        Log.i("ControllerOverlay", "Touch editor: layout saved");
    }

    /** Apply saved button/stick layout. Called after initializeVirtualController. */
    public void applyEditLayout() {
        Context ctx = getContext();
        float density = ctx.getResources().getDisplayMetrics().density;
        SharedPreferences sp = ctx.getSharedPreferences("virtual_controller",
                Context.MODE_PRIVATE);
        float alpha = sp.getFloat("btn_alpha", 1f);
        // Wait for layout pass so getWidth()/positions are valid
        post(new Runnable() {
            @Override public void run() {
                for (int id : EDITABLE_VIEW_IDS) {
                    View v = findViewById(id);
                    if (v == null) continue;
                    String n = editNameFor(v);
                    if (n == null) continue;
                    v.setAlpha(alpha);
                    if (n.equals("stick") || n.equals("stick2")) {
                        continue; // stick handled by applyStickConfig margins
                    }
                    if (sp.contains("btn_" + n + "_x")) {
                        float xDp = sp.getFloat("btn_" + n + "_x", 0);
                        float yDp = sp.getFloat("btn_" + n + "_y", 0);
                        float sDp = sp.getFloat("btn_" + n + "_size", 55);
                        ViewGroup.LayoutParams lp = v.getLayoutParams();
                        lp.width = Math.round(sDp * density);
                        lp.height = Math.round(sDp * density);
                        v.setLayoutParams(lp);
                        v.setX(xDp * density);
                        v.setY(yDp * density);
                    }
                }
            }
        });
    }

    private void resetEditLayout() {
        getContext().getSharedPreferences("virtual_controller", Context.MODE_PRIVATE)
                .edit().clear().apply();
        // Delete INI override too so defaults return
        try {
            File ini = new File(getContext().getExternalFilesDir(null), "virtual_controller.ini");
            if (ini.isFile()) ini.delete();
        } catch (Exception e) {
            Log.w("ControllerOverlay", "reset ini: " + e);
        }
        // Re-run stock initialization to restore XML defaults, then re-apply (empty) layout
        isInitialized = false;
        initializeVirtualController();
        applyEditLayout();
        Log.i("ControllerOverlay", "Touch editor: layout reset to defaults");
    }

}
