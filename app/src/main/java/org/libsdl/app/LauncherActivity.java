package org.libsdl.app;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import java.io.File;
import java.io.InputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;
import org.ikemen_engine.ikemen_go.R;

/**
 * PIASH MUGEN cinematic launcher.
 * Shows branding/artwork, handles game folder selection,
 * then launches SDLActivity (the Ikemen GO engine).
 */
public class LauncherActivity extends Activity {

    private static final int FOLDER_PICKER_CODE = 42;
    private SharedPreferences mSharedPrefs;
    private Button mStartButton;
    private Button mFolderButton;
    private TextView mFolderPathText;
    private Button mThemeButton;
    private TextView mThemeStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Fullscreen cinematic
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_launcher);

        mSharedPrefs = getSharedPreferences(getString(R.string.prefs_key), MODE_PRIVATE);

        mStartButton = findViewById(R.id.btn_start_game);
        mFolderButton = findViewById(R.id.btn_select_folder);
        mFolderPathText = findViewById(R.id.tv_folder_path);
        mThemeButton = findViewById(R.id.btn_install_theme);
        mThemeStatus = findViewById(R.id.tv_theme_status);

        mThemeButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { installPiashMugenTheme(); }
        });

        mFolderButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { checkAndPickFolder(); }
        });

        mStartButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { launchGame(); }
        });

        refreshUi();
        startCinematicAnimations();
    }

    /**
     * Mind-blowing launcher animations:
     * - Ken Burns slow zoom/pan on the key-art background
     * - Breathing red glow at the bottom
     * - Staggered overshoot entrance for the buttons
     * - Pulsing START GAME button
     */
    private void startCinematicAnimations() {
        // 1. Ken Burns: slow zoom in/out on background, alternate pan
        final ImageView bg = findViewById(R.id.iv_bg);
        if (bg != null) {
            bg.post(new Runnable() {
                @Override public void run() {
                    ObjectAnimator zoomX = ObjectAnimator.ofFloat(bg, "scaleX", 1.0f, 1.12f);
                    ObjectAnimator zoomY = ObjectAnimator.ofFloat(bg, "scaleY", 1.0f, 1.12f);
                    ObjectAnimator panX = ObjectAnimator.ofFloat(bg, "translationX", 0f, -30f);
                    AnimatorSet kenBurns = new AnimatorSet();
                    kenBurns.playTogether(zoomX, zoomY, panX);
                    kenBurns.setDuration(12000);
                    kenBurns.setInterpolator(new AccelerateDecelerateInterpolator());
                    // Ping-pong forever
                    kenBurns.addListener(new android.animation.AnimatorListenerAdapter() {
                        private boolean zoomed = false;
                        @Override public void onAnimationEnd(android.animation.Animator animation) {
                            zoomed = !zoomed;
                            float s = zoomed ? 1.0f : 1.12f;
                            float tx = zoomed ? 0f : -30f;
                            ObjectAnimator zx = ObjectAnimator.ofFloat(bg, "scaleX", bg.getScaleX(), s);
                            ObjectAnimator zy = ObjectAnimator.ofFloat(bg, "scaleY", bg.getScaleY(), s);
                            ObjectAnimator px = ObjectAnimator.ofFloat(bg, "translationX", bg.getTranslationX(), tx);
                            AnimatorSet back = new AnimatorSet();
                            back.playTogether(zx, zy, px);
                            back.setDuration(12000);
                            back.setInterpolator(new AccelerateDecelerateInterpolator());
                            back.addListener(this);
                            back.start();
                        }
                    });
                    kenBurns.start();
                }
            });
        }

        // 2. Breathing bottom glow
        final View glow = findViewById(R.id.v_glow);
        if (glow != null) {
            ObjectAnimator breathe = ObjectAnimator.ofFloat(glow, "alpha", 0.35f, 1.0f);
            breathe.setDuration(2200);
            breathe.setRepeatMode(ValueAnimator.REVERSE);
            breathe.setRepeatCount(ValueAnimator.INFINITE);
            breathe.setInterpolator(new AccelerateDecelerateInterpolator());
            breathe.start();
        }

        // 3. Staggered button entrance with overshoot
        View[] buttons = {mFolderButton, mStartButton, mThemeButton};
        for (int i = 0; i < buttons.length; i++) {
            final View b = buttons[i];
            b.setAlpha(0f);
            b.setTranslationY(120f);
            b.setScaleX(0.7f);
            b.setScaleY(0.7f);
            b.animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setStartDelay(80 + i * 90)
                .setDuration(380)
                .setInterpolator(new OvershootInterpolator(1.4f))
                .start();
        }

        // 4. Status text fade in
        final View status = findViewById(R.id.layout_status);
        if (status != null) {
            status.setAlpha(0f);
            status.animate().alpha(1f).setStartDelay(250).setDuration(400).start();
        }

        // 5. START button heartbeat pulse (after entrance)
        mStartButton.postDelayed(new Runnable() {
            @Override public void run() {
                ObjectAnimator pulseX = ObjectAnimator.ofFloat(mStartButton, "scaleX", 1f, 1.06f);
                ObjectAnimator pulseY = ObjectAnimator.ofFloat(mStartButton, "scaleY", 1f, 1.06f);
                AnimatorSet pulse = new AnimatorSet();
                pulse.playTogether(pulseX, pulseY);
                pulse.setDuration(900);
                pulseX.setRepeatMode(ValueAnimator.REVERSE);
                pulseX.setRepeatCount(ValueAnimator.INFINITE);
                pulseY.setRepeatMode(ValueAnimator.REVERSE);
                pulseY.setRepeatCount(ValueAnimator.INFINITE);
                pulse.setInterpolator(new AccelerateDecelerateInterpolator());
                pulse.start();
            }
        }, 700);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshUi();
    }

    private String getSavedFolder() {
        return mSharedPrefs.getString(getString(R.string.game_folder_key), "");
    }

    private void refreshUi() {
        String folder = getSavedFolder();
        boolean hasFolder = folder != null && !folder.isEmpty();
        mStartButton.setEnabled(hasFolder);
        mStartButton.setAlpha(hasFolder ? 1.0f : 0.4f);
        if (hasFolder) {
            mFolderPathText.setText(folder);
        } else {
            mFolderPathText.setText("No game folder selected");
        }
    }

    private void ensureThemeApplied() {
        try {
            String folder = getSavedFolder();
            if (folder == null || folder.isEmpty()) return;
            File sysDef = new File(folder, "data/piashmugen/system.def");
            if (!sysDef.exists()) return;
            File configFile = new File(folder, "save/config.ini");
            String current = readConfigMotif(configFile);
            if (current == null || !current.contains("piashmugen")) {
                updateConfigMotif(configFile, "data/piashmugen/system.def");
            }
        } catch (Exception ignored) {}
    }

    private void launchGame() {
        ensureThemeApplied();
        // Instant tap feedback: the engine needs a few seconds to boot,
        // so acknowledge the tap right away instead of a dead pause.
        mStartButton.setEnabled(false);
        mStartButton.setAlpha(0.4f);
        if (mThemeStatus != null) {
            mThemeStatus.setText("LOADING...");
            mThemeStatus.setTextColor(0xFFFF5555);
        }
        Intent intent = new Intent(this, SDLActivity.class);
        startActivity(intent);
    }


    private void installPiashMugenTheme() {
        String folder = getSavedFolder();
        if (folder == null || folder.isEmpty()) {
            mThemeStatus.setText("Select a game folder first");
            mThemeStatus.setTextColor(0xFFFF6666);
            return;
        }
        try {
            // Copy motif files from assets to game folder
            File themeDir = new File(folder, "data/piashmugen");
            int copied = copyAssetDir("piashmugen", themeDir);
            // Verify critical files
            File sysDef = new File(themeDir, "system.def");
            File ttf = new File(themeDir, "font/yagami.ttf");
            if (!sysDef.exists()) {
                mThemeStatus.setText("Install failed: system.def not copied");
                mThemeStatus.setTextColor(0xFFFF6666);
                return;
            }
            // Update save/config.ini Motif setting
            File configFile = new File(folder, "save/config.ini");
            updateConfigMotif(configFile, "data/piashmugen/system.def");
            // Verify config was written
            String motifInConfig = readConfigMotif(configFile);
            if (motifInConfig != null && motifInConfig.contains("piashmugen")) {
                mThemeStatus.setText("PIASH MUGEN theme installed! (" + copied + " files)");
                mThemeStatus.setTextColor(0xFF66FF66);
            } else {
                mThemeStatus.setText("Files copied but config not updated");
                mThemeStatus.setTextColor(0xFFFFAA00);
            }
        } catch (Exception e) {
            mThemeStatus.setText("Install failed: " + e.getMessage());
            mThemeStatus.setTextColor(0xFFFF6666);
        }
    }

    // NOTE: the engine (Ikemen GO) reads Motif from the [Config] section
    // (see src/config.go: `} ini:"Config"`). Writing it under [Options]
    // is silently ignored by the engine.
    private String readConfigMotif(File configFile) throws Exception {
        if (!configFile.exists()) return null;
        BufferedReader br = new BufferedReader(new FileReader(configFile));
        String line;
        boolean inConfig = false;
        try {
            while ((line = br.readLine()) != null) {
                String t = line.trim();
                if (t.startsWith("[") && t.endsWith("]")) {
                    inConfig = t.equalsIgnoreCase("[Config]");
                } else if (inConfig && t.toLowerCase().startsWith("motif")) {
                    int eq = t.indexOf('=');
                    if (eq >= 0) return t.substring(eq + 1).trim();
                }
            }
        } finally { br.close(); }
        return null;
    }

    private int copyAssetDir(String assetPath, File destDir) throws Exception {
        int count = 0;
        String[] files = getAssets().list(assetPath);
        if (!destDir.exists()) destDir.mkdirs();
        if (files == null || files.length == 0) return count;
        for (String f : files) {
            String subPath = assetPath + "/" + f;
            File outFile = new File(destDir, f);
            String[] sub = getAssets().list(subPath);
            if (sub != null && sub.length > 0) {
                count += copyAssetDir(subPath, outFile);
            } else {
                InputStream in = getAssets().open(subPath);
                OutputStream out = new FileOutputStream(outFile);
                byte[] buf = new byte[8192];
                int len;
                while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
                in.close(); out.close();
                count++;
            }
        }
        return count;
    }

    private void updateConfigMotif(File configFile, String motifPath) throws Exception {
        List<String> lines = new ArrayList<>();
        boolean inConfig = false;
        boolean motifSet = false;
        if (configFile.exists()) {
            BufferedReader br = new BufferedReader(new FileReader(configFile));
            String line;
            while ((line = br.readLine()) != null) {
                String t = line.trim();
                if (t.startsWith("[") && t.endsWith("]")) {
                    inConfig = t.equalsIgnoreCase("[Config]");
                }
                if (inConfig && t.toLowerCase().startsWith("motif")) {
                    int eq = line.indexOf('=');
                    if (eq >= 0) {
                        line = line.substring(0, eq + 1) + " " + motifPath;
                        motifSet = true;
                    }
                } else if (!inConfig && t.toLowerCase().startsWith("motif")) {
                    // Stale Motif under another section (e.g. [Options]) is
                    // ignored by the engine; neutralize it to avoid confusion.
                    if (!t.startsWith(";")) line = "; " + line;
                }
                lines.add(line);
            }
            br.close();
        }
        if (!motifSet) {
            // Add [Config] section or append Motif
            boolean hasConfig = false;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).trim().equalsIgnoreCase("[Config]")) {
                    hasConfig = true;
                    lines.add(i + 1, "Motif = " + motifPath);
                    break;
                }
            }
            if (!hasConfig) {
                lines.add("[Config]");
                lines.add("Motif = " + motifPath);
            }
        }
        File parent = configFile.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        FileWriter fw = new FileWriter(configFile);
        for (String l : lines) fw.write(l + "\n");
        fw.close();
    }

    // ---- Folder picker (same logic as SDLActivity) ----


    public void checkAndPickFolder() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.addCategory("android.intent.category.DEFAULT");
                    intent.setData(Uri.parse(String.format("package:%s", getPackageName())));
                    startActivity(intent);
                } catch (Exception e) {
                    Intent intent = new Intent();
                    intent.setAction(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                    startActivity(intent);
                }
            } else {
                openDirectoryPicker();
            }
        } else {
            openDirectoryPicker();
        }
    }

    public void openDirectoryPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        startActivityForResult(intent, FOLDER_PICKER_CODE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FOLDER_PICKER_CODE && resultCode == RESULT_OK && data != null) {
            Uri treeUri = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(treeUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Exception ignored) {}
            String selectedPath = getFullPathFromTreeUri(treeUri);
            if (selectedPath == null || selectedPath.isEmpty()) {
                File ext = getExternalFilesDir(null);
                if (ext != null) selectedPath = ext.getAbsolutePath();
            }
            if (selectedPath != null) {
                mSharedPrefs.edit().putString(getString(R.string.game_folder_key), selectedPath).apply();
            }
        }
        refreshUi();
    }

    public String getFullPathFromTreeUri(Uri treeUri) {
        if (treeUri == null) return null;
        String treeId = DocumentsContract.getTreeDocumentId(treeUri);
        String[] split = treeId.split(":");
        String type = split[0];
        String path = (split.length > 1) ? split[1] : "";
        if ("primary".equalsIgnoreCase(type)) {
            return Environment.getExternalStorageDirectory() + "/" + path;
        } else {
            File[] externalDirs = getExternalFilesDirs(null);
            for (File f : externalDirs) {
                if (f != null) {
                    String absPath = f.getAbsolutePath();
                    if (absPath.contains(type)) {
                        return absPath.split("/Android/")[0] + "/" + path;
                    }
                }
            }
        }
        return null;
    }
}
