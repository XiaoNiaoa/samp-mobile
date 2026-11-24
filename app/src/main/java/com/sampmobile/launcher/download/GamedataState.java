package com.sampmobile.launcher.download;

import android.content.Context;

import com.sampmobile.launcher.utils.GameDataImporter;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 数据就绪判定 —— 只此一处，避免 Splash 与下载任务各写一份导致口径漂移。
 *
 * 判定规则：**安装记录存在** 且 **GTA 核心文件齐全**。
 *
 * 为什么不是只看核心文件：
 *   如果数据包只含联机资源（不含 texdb/gta3.img 等），每次启动都会判"未就绪"，
 *   形成"下完 → 重启 → 又要求下载"的死循环。
 *
 * 为什么不是只看安装记录：
 *   记录写了但文件被用户从文件管理器清掉，游戏进去会崩。核心文件是最终凭据。
 */
public final class GamedataState {

    private GamedataState() {}

    /** 安装记录文件（与 ChunkDownloader 的缓存目录一致）。 */
    public static File stateFile(Context context) {
        File root = context.getExternalFilesDir(null);
        if (root == null) root = new File(context.getFilesDir(), "ext");
        return new File(new File(root, ".dl"), "installed.json");
    }

    /** 已安装版本号；没有记录返回 null。 */
    public static String installedVersion(Context context) {
        File f = stateFile(context);
        if (!f.isFile()) return null;
        try (InputStream in = new FileInputStream(f)) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new JSONObject(new String(bos.toByteArray(), StandardCharsets.UTF_8))
                    .optString("version", null);
        } catch (Exception e) {
            return null;
        }
    }

    /** 数据是否就绪（可以被游戏直接使用）。 */
    public static boolean isReady(Context context) {
        if (installedVersion(context) == null) return false;
        File root = context.getExternalFilesDir(null);
        return GameDataImporter.isGameDataReady(root);
    }

    /** 缺哪些核心文件（空表示齐全），用于给玩家更具体的提示。 */
    public static java.util.List<String> missingCoreFiles(Context context) {
        return GameDataImporter.findMissingCoreFiles(context.getExternalFilesDir(null));
    }
}
