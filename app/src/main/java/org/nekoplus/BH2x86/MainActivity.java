package org.nekoplus.BH2x86;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * MainActivity - 使用 su 执行 shell 命令来读写目标文件
 * 已修改：根据读取到的当前架构自动禁用/允许按钮；并在后台二次校验避免不必要操作。
 */
public class MainActivity extends Activity {
    private static final String TARGET_PATH = "/data/system/etc/mumu-configs/abi-select-android12.config";
    private static final String BACKUP_PATH = TARGET_PATH + ".bak";
    private static final String MARKER = "HSoDv2Original";
    private static final String ORIG_ARCH = "arm64-v8a";
    private static final String X86 = "x86";
    private static final int PAD_LEN = 9;

    private TextView archText;
    private Button btnChange;
    private Button btnRestore;
    private boolean rootAvailable = false;
    private String currentArch = null; // "x86" / "arm64-v8a" / null

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        archText = (TextView) findViewById(R.id.archText);
        btnChange = (Button) findViewById(R.id.btnChange);
        btnRestore = (Button) findViewById(R.id.btnRestore);

        // 初始都禁用，等待检测完成
        btnChange.setEnabled(false);
        btnRestore.setEnabled(false);

        // 启动时检测并请求 root（简单检测 su 可用性）
        new CheckRootTask().execute();

        btnChange.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					new ModifyArchTask(ModifyArchTask.Action.TO_X86).execute();
				}
			});

        btnRestore.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					new ModifyArchTask(ModifyArchTask.Action.RESTORE).execute();
				}
			});
    }

    // 显示 AlertDialog（UI 线程安全）
    private void showAlert(String title, String message) {
        try {
            AlertDialog.Builder b = new AlertDialog.Builder(this);
            b.setTitle(title);
            b.setMessage(message);
            b.setPositiveButton("确定", null);
            b.show();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // 运行 su -c "<cmd>" 并返回结果
    private static class ShellResult {
        int exitCode;
        String stdout;
        String stderr;
    }

    private ShellResult runSuCommand(String cmd) {
        ShellResult res = new ShellResult();
        Process p = null;
        BufferedReader in = null;
        BufferedReader err = null;
        try {
            p = Runtime.getRuntime().exec(new String[] { "su", "-c", cmd });
            in = new BufferedReader(new InputStreamReader(p.getInputStream()));
            err = new BufferedReader(new InputStreamReader(p.getErrorStream()));

            StringBuilder outSb = new StringBuilder();
            StringBuilder errSb = new StringBuilder();
            String line;
            while ((line = in.readLine()) != null) {
                outSb.append(line).append('\n');
            }
            while ((line = err.readLine()) != null) {
                errSb.append(line).append('\n');
            }

            int rc = p.waitFor();
            res.exitCode = rc;
            res.stdout = outSb.toString();
            res.stderr = errSb.toString();
            return res;
        } catch (Exception e) {
            res.exitCode = -1;
            res.stdout = "";
            res.stderr = e.getMessage();
            return res;
        } finally {
            try { if (in != null) in.close(); } catch (IOException ignored) {}
            try { if (err != null) err.close(); } catch (IOException ignored) {}
            if (p != null) p.destroy();
        }
    }

    // 检查 root 可用性（阻塞）
    private boolean checkRootAvailableBlocking(StringBuilder outMsg) {
        ShellResult r = runSuCommand("id");
        if (r.exitCode == 0 && r.stdout != null && r.stdout.contains("uid=0")) {
            return true;
        } else {
            outMsg.append("su 返回 exit=").append(r.exitCode)
				.append(", stdout=").append(r.stdout)
				.append(", stderr=").append(r.stderr);
            return false;
        }
    }

    // 以 root 读取文件为行列表
    private List<String> readFileAsRoot(String path, StringBuilder errorMsg) {
        ShellResult r = runSuCommand("cat '" + path + "'");
        if (r.exitCode != 0) {
            errorMsg.append("通过 su 读取文件失败，exit=").append(r.exitCode)
				.append(", stderr=").append(r.stderr);
            return null;
        }
        List<String> lines = new ArrayList<String>();
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(new java.io.ByteArrayInputStream(r.stdout.getBytes("UTF-8"))));
            String l;
            while ((l = br.readLine()) != null) {
                lines.add(l);
            }
            return lines;
        } catch (Exception e) {
            errorMsg.append("解析读取内容失败: ").append(e.getMessage());
            return null;
        } finally {
            try { if (br != null) br.close(); } catch (IOException ignored) {}
        }
    }

    // 将临时文件复制为 root 覆盖目标（并先备份已在上层处理）
    private boolean copyTempToTargetAsRoot(String tempPath, String targetPath, StringBuilder errorMsg) {
        ShellResult rc = runSuCommand("cp '" + tempPath + "' '" + targetPath + "'");
        if (rc.exitCode != 0) {
            errorMsg.append("cp 临时到目标失败，exit=").append(rc.exitCode).append(", stderr=").append(rc.stderr);
            return false;
        }
        ShellResult rchmod = runSuCommand("chmod 0644 '" + targetPath + "'");
        if (rchmod.exitCode != 0) {
            errorMsg.append("设置权限失败，exit=").append(rchmod.exitCode).append(", stderr=").append(rchmod.stderr);
            // 非致命
        }
        return true;
    }

    // 右填空格到 len
    private static String padRight(String s, int len) {
        if (s == null) s = "";
        if (s.length() >= len) return s.substring(0, len);
        StringBuilder sb = new StringBuilder(len);
        sb.append(s);
        for (int i = s.length(); i < len; i++) sb.append(' ');
        return sb.toString();
    }

    // 根据 currentArch 更新按钮状态（UI 线程调用）
    private void updateButtonsByArch() {
        if (X86.equals(currentArch)) {
            btnChange.setEnabled(false); // 已是 x86，不允许改x86
            btnRestore.setEnabled(true);
        } else if (ORIG_ARCH.equals(currentArch)) {
            btnChange.setEnabled(true);
            btnRestore.setEnabled(false); // 已是 arm，不允许还原
        } else {
            // 未知或其他，允许两者（也可改为都禁用）
            btnChange.setEnabled(true);
            btnRestore.setEnabled(true);
        }
    }

    // Check root AsyncTask
    private class CheckRootTask extends AsyncTask<Void, Void, Boolean> {
        private String error = null;

        @Override
        protected Boolean doInBackground(Void... params) {
            StringBuilder msg = new StringBuilder();
            boolean ok = checkRootAvailableBlocking(msg);
            if (!ok) error = msg.toString();
            return ok;
        }

        @Override
        protected void onPostExecute(Boolean ok) {
            rootAvailable = ok;
            if (!ok) {
                archText.setText("当前架构: root 未可用");
                currentArch = null;
                updateButtonsByArch();
                showAlert("未获得 root", "应用未获得 root 权限，若要修改系统文件请允许 su 并重试。\n详情: " + (error != null ? error : ""));
            } else {
                new LoadArchTask().execute();
            }
        }
    }

    // Load current arch via su cat
    private class LoadArchTask extends AsyncTask<Void, Void, String> {
        private String error = null;

        @Override
        protected String doInBackground(Void... params) {
            StringBuilder err = new StringBuilder();
            List<String> lines = readFileAsRoot(TARGET_PATH, err);
            if (lines == null) {
                error = err.toString();
                return null;
            }
            for (String line : lines) {
                if (line.contains(MARKER)) {
                    if (line.contains(ORIG_ARCH)) return ORIG_ARCH;
                    if (line.contains(X86)) return X86;
                    if (line.contains("arm64")) return ORIG_ARCH;
                }
            }
            error = "未在文件中找到包含标记的行: " + MARKER;
            return null;
        }

        @Override
        protected void onPostExecute(String arch) {
            currentArch = arch;
            if (arch != null) {
                archText.setText("当前架构: " + arch);
            } else {
                archText.setText("当前架构: 未知");
                showAlert("读取失败", error != null ? error : "未知错误（可能未授予 root）");
            }
            updateButtonsByArch();
        }
    }

    // Modify task: TO_X86 会备份（覆盖 BACKUP_PATH），RESTORE 不使用备份
    private class ModifyArchTask extends AsyncTask<Void, Void, Boolean> {
        enum Action { TO_X86, RESTORE }
        private final Action action;
        private String error = null;

        ModifyArchTask(Action action) {
            this.action = action;
        }

        @Override
        protected void onPreExecute() {
            // 在操作期间禁用按钮，避免重复点击/并发
            btnChange.setEnabled(false);
            btnRestore.setEnabled(false);
        }

        @Override
        protected Boolean doInBackground(Void... params) {
            if (!rootAvailable) {
                StringBuilder msg = new StringBuilder();
                if (!checkRootAvailableBlocking(msg)) {
                    error = "设备未授予 root 或 su 不存在: " + msg.toString();
                    return false;
                } else {
                    rootAvailable = true;
                }
            }

            // 读取文件
            StringBuilder readErr = new StringBuilder();
            List<String> lines = readFileAsRoot(TARGET_PATH, readErr);
            if (lines == null) {
                error = "读取文件失败: " + readErr.toString();
                return false;
            }

            // 先检测当前实际架构（以文件为准）并阻止不允许的操作
            String detected = null;
            for (String line : lines) {
                if (line.contains(MARKER)) {
                    if (line.contains(ORIG_ARCH)) detected = ORIG_ARCH;
                    else if (line.contains(X86)) detected = X86;
                    else if (line.contains("arm64")) detected = ORIG_ARCH;
                    break;
                }
            }

            if (action == Action.TO_X86 && X86.equals(detected)) {
                error = "检测到当前已为 x86，禁止执行“改x86”。";
                return false;
            }
            if (action == Action.RESTORE && ORIG_ARCH.equals(detected)) {
                error = "检测到当前已为 arm64-v8a，禁止执行“还原”。";
                return false;
            }

            boolean changed = false;
            List<String> outLines = new ArrayList<String>();
            boolean willBackup = false; // 仅在确实要把 arm->x86 时备份

            for (String line : lines) {
                if (!changed && line.contains(MARKER)) {
                    if (action == Action.TO_X86) {
                        if (line.contains(ORIG_ARCH)) {
                            // 将 arm64-v8a 替换为 x86（并右填空格到 PAD_LEN）
                            String padded = padRight(X86, PAD_LEN);
                            line = line.replaceFirst(ORIG_ARCH, padded);
                            changed = true;
                            willBackup = true; // 实际替换时备份原文件
                        } else if (line.contains(X86)) {
                            // 已经是 x86（不应到这里，因为早被阻止），但为了保险标记 changed=true
                            changed = true;
                        } else {
                            error = "定位到标记行，但未检测到可替换的架构字符串。";
                            return false;
                        }
                    } else { // RESTORE
                        int idx = line.indexOf(X86);
                        if (idx >= 0) {
                            int replaceEnd = Math.min(idx + PAD_LEN, line.length());
                            String before = line.substring(0, idx);
                            String after = line.substring(replaceEnd);
                            line = before + ORIG_ARCH + after;
                            changed = true;
                        } else if (line.contains(ORIG_ARCH)) {
                            changed = true;
                        } else {
                            error = "定位到标记行，但未检测到可还原的 x86 字符串。";
                            return false;
                        }
                    }
                }
                outLines.add(line);
            }

            if (!changed) {
                error = "未在文件中找到包含标记的行或无需修改。";
                return false;
            }

            // 写入临时文件到应用缓存
            File temp = new File(getCacheDir(), "abi-select-temp.config");
            BufferedWriter bw = null;
            try {
                bw = new BufferedWriter(new FileWriter(temp, false));
                for (int i = 0; i < outLines.size(); i++) {
                    bw.write(outLines.get(i));
                    if (i < outLines.size() - 1) bw.newLine();
                }
                bw.flush();
            } catch (Exception e) {
                error = "写入临时文件失败: " + e.getMessage();
                return false;
            } finally {
                if (bw != null) {
                    try { bw.close(); } catch (IOException ignored) {}
                }
            }

            // 如果确实要把 arm->x86，则先备份（覆盖 BACKUP_PATH）
            if (willBackup) {
                ShellResult rb = runSuCommand("cp '" + TARGET_PATH + "' '" + BACKUP_PATH + "'");
                if (rb.exitCode != 0) {
                    error = "备份原文件失败，exit=" + rb.exitCode + ", stderr=" + rb.stderr;
                    return false;
                }
            }

            // 使用 su 将临时文件复制覆盖目标
            StringBuilder writeErr = new StringBuilder();
            boolean ok = copyTempToTargetAsRoot(temp.getAbsolutePath(), TARGET_PATH, writeErr);
            if (!ok) {
                error = "以 root 覆盖目标文件失败: " + writeErr.toString();
                return false;
            }

            return true;
        }

        @Override
        protected void onPostExecute(Boolean success) {
            if (success) {
                // 读取最新状态并更新 UI（LoadArchTask 会设置 currentArch 并更新按钮）
                new LoadArchTask().execute();
                if (action == Action.TO_X86) {
                    showAlert("成功", "已改为x86，并已创建/覆盖备份：" + BACKUP_PATH);
                } else {
                    showAlert("成功", "已在文件中将x86替回为 " + ORIG_ARCH);
                }
            } else {
                String msg = error != null ? error : "未知错误";
                showAlert("操作失败", msg + "\n\n注意：若 su 未授权或设备无 su，操作会失败。");
                // 读取最新状态以恢复按钮（如果可能）
                new LoadArchTask().execute();
            }
        }
    }
}
