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
import android.widget.Button;
import android.widget.TextView;
import java.io.File;
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

        mFolderButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { checkAndPickFolder(); }
        });

        mStartButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { launchGame(); }
        });

        refreshUi();
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

    private void launchGame() {
        Intent intent = new Intent(this, SDLActivity.class);
        startActivity(intent);
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
