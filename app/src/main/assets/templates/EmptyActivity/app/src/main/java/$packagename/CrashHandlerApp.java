package $packagename;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Process;
import android.util.TypedValue;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.PrintWriter;
import java.io.StringWriter;

public class CrashHandlerApp extends Application {

    private static final String PREFS_NAME = "crash_handler";
    private static final String KEY_CRASH_LOG = "crash_log";
    private boolean crashDialogShown = false;

    @Override
    public void onCreate() {
        super.onCreate();

        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                String stackTrace = sw.toString();

                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                        .edit()
                        .putString(KEY_CRASH_LOG, stackTrace)
                        .commit();

                Process.killProcess(Process.myPid());
                System.exit(1);
            }
        });

        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(Activity activity, Bundle savedInstanceState) {}

            @Override
            public void onActivityStarted(Activity activity) {}

            @Override
            public void onActivityResumed(Activity activity) {
                if (crashDialogShown) {
                    return;
                }
                SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                String crashLog = prefs.getString(KEY_CRASH_LOG, null);
                if (crashLog != null) {
                    crashDialogShown = true;
                    prefs.edit().remove(KEY_CRASH_LOG).apply();
                    showCrashDialog(activity, crashLog);
                }
            }

            @Override
            public void onActivityPaused(Activity activity) {}

            @Override
            public void onActivityStopped(Activity activity) {}

            @Override
            public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}

            @Override
            public void onActivityDestroyed(Activity activity) {}
        });
    }

    private void showCrashDialog(Activity activity, final String crashLog) {
        float density = activity.getResources().getDisplayMetrics().density;
        int dp16 = (int) (16 * density);
        int dp12 = (int) (12 * density);
        int dp4 = (int) (4 * density);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp16, dp12, dp16, 0);

        TextView subtitle = new TextView(activity);
        subtitle.setText("The app crashed during the last session. You can copy the error details below to report this issue.");
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        subtitle.setTextColor(Color.parseColor("#666666"));
        subtitle.setPadding(0, 0, 0, dp12);
        root.addView(subtitle);

        ScrollView scrollView = new ScrollView(activity);
        scrollView.setBackgroundColor(Color.parseColor("#F5F5F5"));
        int maxHeight = (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.5f);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, maxHeight);
        scrollView.setLayoutParams(scrollParams);

        TextView logView = new TextView(activity);
        logView.setText(crashLog);
        logView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextColor(Color.parseColor("#333333"));
        logView.setTextIsSelectable(true);
        logView.setPadding(dp12, dp12, dp12, dp12);
        scrollView.addView(logView);
        root.addView(scrollView);

        new AlertDialog.Builder(activity)
                .setTitle("App Crashed")
                .setView(root)
                .setCancelable(true)
                .setPositiveButton("Copy Log", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(CLIPBOARD_SERVICE);
                        if (clipboard != null) {
                            clipboard.setPrimaryClip(ClipData.newPlainText("Crash Log", "Please help me fix this crash:\n\n" + crashLog));
                            Toast.makeText(activity, "Crash log copied", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }
}
