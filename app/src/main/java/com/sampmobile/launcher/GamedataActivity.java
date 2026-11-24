package com.sampmobile.launcher;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.sampmobile.launcher.download.GamedataInstallerTask;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 首次启动时的数据准备页：下载游戏本体 + 联机数据包，解压安装到私有目录。
 *
 * 流程与状态机都在 {@link GamedataInstallerTask} 里，这里只负责：
 *   - 把后台线程的回调切到主线程更新 UI
 *   - 取消 / 重试按钮
 *   - 完成后跳回启动器
 *
 * 默认的清单地址从 BuildConfig 或 strings 读；没配置时页面直接给出提示，
 * 不会去连一个空 URL 然后卡住。
 */
public class GamedataActivity extends AppCompatActivity
        implements GamedataInstallerTask.Listener {

    private static final String TAG = "GamedataActivity";


    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean running = new AtomicBoolean(false);

    private ProgressBar progressBar;
    private TextView percentText;
    private TextView statusText;
    private TextView subtitleText;
    private Button actionButton;

    private GamedataInstallerTask task;
    private Thread worker;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_gamedata);

        progressBar = findViewById(R.id.progress_bar);
        percentText = findViewById(R.id.percent_text);
        statusText = findViewById(R.id.status_text);
        subtitleText = findViewById(R.id.subtitle_text);
        actionButton = findViewById(R.id.action_button);

        actionButton.setOnClickListener(v -> {
            if (running.get()) {
                cancelDownload();
            } else {
                start();
            }
        });

        start();
    }

    /** 清单地址从 res/values/strings.xml 的 gamedata_manifest_url 读取，便于发布时替换。 */
    private String manifestUrl() {
        return getString(R.string.gamedata_manifest_url);
    }

    private void start() {
        if (running.get()) return;

        String url = manifestUrl();
        if (url.isEmpty()) {
            setStatus("未配置数据包地址（strings.xml 的 gamedata_manifest_url 为空）", true);
            progressBar.setProgress(0);
            percentText.setText("—");
            actionButton.setText(R.string.gamedata_retry);
            actionButton.setEnabled(false);
            return;
        }

        running.set(true);
        actionButton.setEnabled(true);
        actionButton.setText(R.string.gamedata_cancel);
        statusText.setText(R.string.gamedata_connecting);
        percentText.setText("0%");
        progressBar.setProgress(0);

        task = new GamedataInstallerTask(this, url);
        worker = new Thread(() -> task.run(this), "gamedata-install");
        worker.start();
    }

    private void cancelDownload() {
        if (task != null) task.cancel();
        // 工作线程要跑到下一个检查点才会真的退出（可能还在读网络）。
        // 这里只把按钮置灰，等 onError/onDone 回调里再恢复成「重试」，
        // 否则 running 仍为 true 时界面已经显示可重试，点了会静默无效。
        actionButton.setEnabled(false);
        actionButton.setText(R.string.gamedata_cancelled);
        setStatus("正在取消…", false);
    }

    // ------------------------------------------------------- Listener（后台线程）

    @Override
    public void onStage(GamedataInstallerTask.Stage stage) {
        main.post(() -> {
            switch (stage) {
                case FETCHING_MANIFEST:
                    statusText.setText(R.string.gamedata_connecting);
                    break;
                case CHECKING:
                    statusText.setText("正在检查数据更新…");
                    break;
                case DOWNLOADING:
                    actionButton.setText(R.string.gamedata_cancel);
                    break;
                case VERIFYING:
                    setStatus("正在校验数据完整性…", false);
                    break;
                case EXTRACTING:
                    setStatus("正在安装数据…", false);
                    break;
                case ACTIVATING:
                    setStatus("正在启用数据…", false);
                    break;
                case READY:
                    break;
                default:
                    break;
            }
        });
    }

    /** 进度更新至少间隔 200ms；2GB 数据每 64KB 回调一次，不节流会 post 三万次。 */
    private static final long PROGRESS_THROTTLE_MS = 200;
    private long lastProgressPostMs = 0;

    @Override
    public void onProgress(long bytesDone, long bytesTotal) {
        final long now = System.currentTimeMillis();
        final int perMille = bytesTotal > 0
                ? (int) Math.min(1000, bytesDone * 1000 / bytesTotal)
                : 0;
        // 100% 与首次必然刷新，其余按节流窗口丢弃
        final boolean force = perMille >= 1000 || lastProgressPostMs == 0;
        if (!force && now - lastProgressPostMs < PROGRESS_THROTTLE_MS) return;
        lastProgressPostMs = now;

        main.post(() -> {
            if (bytesTotal <= 0) return;
            progressBar.setProgress(perMille);
            percentText.setText(String.format(Locale.US, "%d%%", perMille / 10));
            subtitleText.setText(String.format(Locale.US, "%s / %s",
                    human(bytesDone), human(bytesTotal)));
        });
    }

    @Override
    public void onStatus(String message) {
        main.post(() -> {
            if (message != null && !message.isEmpty()) statusText.setText(message);
        });
    }

    @Override
    public void onError(String message, Throwable cause) {
        running.set(false);
        lastProgressPostMs = 0;
        main.post(() -> {
            setStatus(message, true);
            actionButton.setEnabled(true);
            actionButton.setText(R.string.gamedata_retry);
            Log.e(TAG, "数据准备失败: " + message, cause);
        });
    }

    @Override
    public void onDone(boolean installedNow) {
        running.set(false);
        main.post(() -> {
            progressBar.setProgress(1000);
            percentText.setText("100%");
            setStatus(installedNow ? "数据准备完成" : "数据已就绪", false);
            actionButton.setText(R.string.gamedata_enter);
            actionButton.setEnabled(true);
            actionButton.setOnClickListener(v -> {
                // MainActivity 有 extra_check 校验（防外部拉起），漏传会立刻 finish。
                Intent intent = new Intent(this, MainActivity.class);
                intent.putExtra("extra_check", "sampmobile1337");
                startActivity(intent);
                finish();
            });
        });
    }

    // ------------------------------------------------------------------ 工具

    private void setStatus(String text, boolean error) {
        statusText.setText(text == null ? "" : text);
        statusText.setTextColor(getColor(error
                ? android.R.color.holo_red_light
                : R.color.launcher_text_dim));
    }

    static String human(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb);
        return String.format(Locale.US, "%.2f GB", mb / 1024.0);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (task != null) task.cancel();
        main.removeCallbacksAndMessages(null);
    }
}
