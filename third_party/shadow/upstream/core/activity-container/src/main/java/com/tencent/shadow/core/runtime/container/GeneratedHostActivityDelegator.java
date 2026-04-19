package com.tencent.shadow.core.runtime.container;

import android.app.ActionBar;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.Application;
import android.app.ComponentCaller;
import android.app.Dialog;
import android.app.DirectAction;
import android.app.Fragment;
import android.app.FragmentManager;
import android.app.LoaderManager;
import android.app.PendingIntent;
import android.app.PictureInPictureParams;
import android.app.PictureInPictureUiState;
import android.app.SharedElementCallback;
import android.app.TaskStackBuilder;
import android.app.VoiceInteractor;
import android.app.assist.AssistContent;
import android.content.AttributionSource;
import android.content.BroadcastReceiver;
import android.content.ComponentCallbacks;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextParams;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.LocusId;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.Cursor;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.media.session.MediaController;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.OutcomeReceiver;
import android.os.PersistableBundle;
import android.os.UserHandle;
import android.transition.Scene;
import android.transition.TransitionManager;
import android.util.AttributeSet;
import android.view.ActionMode;
import android.view.ContextMenu;
import android.view.Display;
import android.view.DragAndDropPermissions;
import android.view.DragEvent;
import android.view.KeyEvent;
import android.view.KeyboardShortcutGroup;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.SearchEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toolbar;
import android.window.OnBackInvokedDispatcher;
import android.window.SplashScreen;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.lang.CharSequence;
import java.lang.Class;
import java.lang.ClassLoader;
import java.lang.Object;
import java.lang.Runnable;
import java.lang.String;
import java.lang.Throwable;
import java.lang.Void;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * 由
 * {@link com.tencent.shadow.coding.code_generator.ActivityCodeGenerator}
 * 自动生成
 * HostActivityDelegator作为委托者的接口。主要提供它的委托方法的super方法，
 * 以便Delegate可以通过这个接口调用到Activity的super方法。
 */
public interface GeneratedHostActivityDelegator {
  void dump(String arg0, FileDescriptor arg1, PrintWriter arg2, String[] arg3);

  void clearOverrideActivityTransition(int arg0);

  void dismissKeyboardShortcutsHelper();

  TransitionManager getContentTransitionManager();

  Object getLastNonConfigurationInstance();

  int getMaxNumPictureInPictureActions();

  boolean isActivityTransitionRunning();

  boolean isLocalVoiceInteractionSupported();

  void registerActivityLifecycleCallbacks(Application.ActivityLifecycleCallbacks arg0);

  void registerScreenCaptureCallback(Executor arg0, Activity.ScreenCaptureCallback arg1);

  DragAndDropPermissions requestDragAndDropPermissions(DragEvent arg0);

  void requestOpenInBrowserEducation();

  void requestShowKeyboardShortcuts();

  void setAllowCrossUidActivitySwitchFromBelow(boolean arg0);

  void setContentTransitionManager(TransitionManager arg0);

  void setEnterSharedElementCallback(SharedElementCallback arg0);

  void setExitSharedElementCallback(SharedElementCallback arg0);

  void setProgressBarIndeterminate(boolean arg0);

  void setProgressBarIndeterminateVisibility(boolean arg0);

  void setRecentsScreenshotEnabled(boolean arg0);

  boolean shouldShowRequestPermissionRationale(String arg0);

  boolean shouldShowRequestPermissionRationale(String arg0, int arg1);

  void startPostponedEnterTransition();

  void unregisterActivityLifecycleCallbacks(Application.ActivityLifecycleCallbacks arg0);

  void unregisterComponentCallbacks(ComponentCallbacks arg0);

  void unregisterScreenCaptureCallback(Activity.ScreenCaptureCallback arg0);

  ComponentCaller getCaller();

  Intent getIntent();

  int getTaskId();

  CharSequence getTitle();

  Window getWindow();

  boolean isChild();

  boolean isTaskRoot();

  void setIntent(Intent arg0);

  void setIntent(Intent arg0, ComponentCaller arg1);

  void setTheme(int arg0);

  void setTitle(int arg0);

  void setTitle(CharSequence arg0);

  void setVisible(boolean arg0);

  boolean showAssist(Bundle arg0);

  boolean showDialog(int arg0, Bundle arg1);

  void showDialog(int arg0);

  void addContentView(View arg0, ViewGroup.LayoutParams arg1);

  void closeContextMenu();

  void closeOptionsMenu();

  PendingIntent createPendingResult(int arg0, Intent arg1, int arg2);

  void dismissDialog(int arg0);

  void enterPictureInPictureMode();

  boolean enterPictureInPictureMode(PictureInPictureParams arg0);

  <T extends View> T findViewById(int arg0);

  void finishActivity(int arg0);

  void finishActivityFromChild(Activity arg0, int arg1);

  void finishAffinity();

  void finishAfterTransition();

  void finishAndRemoveTask();

  void finishFromChild(Activity arg0);

  ActionBar getActionBar();

  Application getApplication();

  String getCallingPackage();

  int getChangingConfigurations();

  ComponentName getComponentName();

  Scene getContentScene();

  ComponentCaller getCurrentCaller();

  View getCurrentFocus();

  FragmentManager getFragmentManager();

  ComponentCaller getInitialCaller();

  String getLaunchedFromPackage();

  int getLaunchedFromUid();

  LoaderManager getLoaderManager();

  String getLocalClassName();

  MediaController getMediaController();

  MenuInflater getMenuInflater();

  OnBackInvokedDispatcher getOnBackInvokedDispatcher();

  Intent getParentActivityIntent();

  SharedPreferences getPreferences(int arg0);

  Uri getReferrer();

  int getRequestedOrientation();

  SearchEvent getSearchEvent();

  SplashScreen getSplashScreen();

  Object getSystemService(String arg0);

  int getTitleColor();

  VoiceInteractor getVoiceInteractor();

  int getVolumeControlStream();

  WindowManager getWindowManager();

  boolean hasWindowFocus();

  void invalidateOptionsMenu();

  boolean isFinishing();

  boolean isImmersive();

  boolean isInMultiWindowMode();

  boolean isInPictureInPictureMode();

  boolean isLaunchedFromBubble();

  boolean isVoiceInteraction();

  boolean isVoiceInteractionRoot();

  Cursor managedQuery(Uri arg0, String[] arg1, String arg2, String[] arg3, String arg4);

  boolean moveTaskToBack(boolean arg0);

  boolean navigateUpTo(Intent arg0);

  boolean navigateUpToFromChild(Activity arg0, Intent arg1);

  void openContextMenu(View arg0);

  void openOptionsMenu();

  void overrideActivityTransition(int arg0, int arg1, int arg2, int arg3);

  void overrideActivityTransition(int arg0, int arg1, int arg2);

  void overridePendingTransition(int arg0, int arg1, int arg2);

  void overridePendingTransition(int arg0, int arg1);

  void postponeEnterTransition();

  void registerComponentCallbacks(ComponentCallbacks arg0);

  void registerForContextMenu(View arg0);

  boolean releaseInstance();

  void removeDialog(int arg0);

  void reportFullyDrawn();

  void requestFullscreenMode(int arg0, OutcomeReceiver<Void, Throwable> arg1);

  void requestPermissions(String[] arg0, int arg1);

  void requestPermissions(String[] arg0, int arg1, int arg2);

  boolean requestVisibleBehind(boolean arg0);

  boolean requestWindowFeature(int arg0);

  <T extends View> T requireViewById(int arg0);

  void runOnUiThread(Runnable arg0);

  void setActionBar(Toolbar arg0);

  void setContentView(int arg0);

  void setContentView(View arg0, ViewGroup.LayoutParams arg1);

  void setContentView(View arg0);

  void setDefaultKeyMode(int arg0);

  void setFeatureDrawable(int arg0, Drawable arg1);

  void setFeatureDrawableAlpha(int arg0, int arg1);

  void setFeatureDrawableResource(int arg0, int arg1);

  void setFeatureDrawableUri(int arg0, Uri arg1);

  void setFinishOnTouchOutside(boolean arg0);

  void setImmersive(boolean arg0);

  void setInheritShowWhenLocked(boolean arg0);

  void setLocusContext(LocusId arg0, Bundle arg1);

  void setMediaController(MediaController arg0);

  void setPictureInPictureParams(PictureInPictureParams arg0);

  void setProgress(int arg0);

  void setProgressBarVisibility(boolean arg0);

  void setRequestedOrientation(int arg0);

  void setSecondaryProgress(int arg0);

  void setShouldDockBigOverlays(boolean arg0);

  boolean shouldDockBigOverlays();

  void setShowWhenLocked(boolean arg0);

  void setTaskDescription(ActivityManager.TaskDescription arg0);

  void setTitleColor(int arg0);

  boolean setTranslucent(boolean arg0);

  void setTurnScreenOn(boolean arg0);

  void setVolumeControlStream(int arg0);

  void setVrModeEnabled(boolean arg0, ComponentName arg1) throws
      PackageManager.NameNotFoundException;

  boolean shouldUpRecreateTask(Intent arg0);

  void showLockTaskEscapeMessage();

  ActionMode startActionMode(ActionMode.Callback arg0);

  ActionMode startActionMode(ActionMode.Callback arg0, int arg1);

  void startActivities(Intent[] arg0, Bundle arg1);

  void startActivities(Intent[] arg0);

  void startActivity(Intent arg0, Bundle arg1);

  void startActivity(Intent arg0);

  void startActivityForResult(Intent arg0, int arg1);

  void startActivityForResult(Intent arg0, int arg1, Bundle arg2);

  void startActivityFromChild(Activity arg0, Intent arg1, int arg2, Bundle arg3);

  void startActivityFromChild(Activity arg0, Intent arg1, int arg2);

  void startActivityFromFragment(Fragment arg0, Intent arg1, int arg2, Bundle arg3);

  void startActivityFromFragment(Fragment arg0, Intent arg1, int arg2);

  boolean startActivityIfNeeded(Intent arg0, int arg1);

  boolean startActivityIfNeeded(Intent arg0, int arg1, Bundle arg2);

  void startIntentSender(IntentSender arg0, Intent arg1, int arg2, int arg3, int arg4) throws
      IntentSender.SendIntentException;

  void startIntentSender(IntentSender arg0, Intent arg1, int arg2, int arg3, int arg4, Bundle arg5)
      throws IntentSender.SendIntentException;

  void startIntentSenderForResult(IntentSender arg0, int arg1, Intent arg2, int arg3, int arg4,
      int arg5, Bundle arg6) throws IntentSender.SendIntentException;

  void startIntentSenderForResult(IntentSender arg0, int arg1, Intent arg2, int arg3, int arg4,
      int arg5) throws IntentSender.SendIntentException;

  void startIntentSenderFromChild(Activity arg0, IntentSender arg1, int arg2, Intent arg3, int arg4,
      int arg5, int arg6, Bundle arg7) throws IntentSender.SendIntentException;

  void startIntentSenderFromChild(Activity arg0, IntentSender arg1, int arg2, Intent arg3, int arg4,
      int arg5, int arg6) throws IntentSender.SendIntentException;

  void startLocalVoiceInteraction(Bundle arg0);

  void startLockTask();

  void startManagingCursor(Cursor arg0);

  boolean startNextMatchingActivity(Intent arg0);

  boolean startNextMatchingActivity(Intent arg0, Bundle arg1);

  void startSearch(String arg0, boolean arg1, Bundle arg2, boolean arg3);

  void stopLocalVoiceInteraction();

  void stopLockTask();

  void stopManagingCursor(Cursor arg0);

  void takeKeyEvents(boolean arg0);

  void triggerSearch(String arg0, Bundle arg1);

  void unregisterForContextMenu(View arg0);

  Activity getParent();

  boolean isDestroyed();

  void setResult(int arg0, Intent arg1);

  void setResult(int arg0);

  void setTheme(Resources.Theme arg0);

  AssetManager getAssets();

  Resources.Theme getTheme();

  void applyOverrideConfiguration(Configuration arg0);

  int checkCallingOrSelfPermission(String arg0);

  int checkCallingOrSelfUriPermission(Uri arg0, int arg1);

  int[] checkCallingOrSelfUriPermissions(List<Uri> arg0, int arg1);

  int checkContentUriPermissionFull(Uri arg0, int arg1, int arg2, int arg3);

  Context createDeviceProtectedStorageContext();

  void enforceCallingOrSelfPermission(String arg0, String arg1);

  void enforceCallingOrSelfUriPermission(Uri arg0, int arg1, String arg2);

  void enforceCallingUriPermission(Uri arg0, int arg1, String arg2);

  int getWallpaperDesiredMinimumHeight();

  int getWallpaperDesiredMinimumWidth();

  void registerDeviceIdChangeListener(Executor arg0, IntConsumer arg1);

  void removeStickyBroadcastAsUser(Intent arg0, UserHandle arg1);

  void revokeSelfPermissionsOnKill(Collection<String> arg0);

  void sendStickyOrderedBroadcastAsUser(Intent arg0, UserHandle arg1, BroadcastReceiver arg2,
      Handler arg3, int arg4, String arg5, Bundle arg6);

  void unregisterDeviceIdChangeListener(IntConsumer arg0);

  boolean deleteFile(String arg0);

  String[] fileList();

  File getDataDir();

  File getDir(String arg0, int arg1);

  Display getDisplay();

  File getObbDir();

  File[] getObbDirs();

  ContextParams getParams();

  boolean bindIsolatedService(Intent arg0, int arg1, String arg2, Executor arg3,
      ServiceConnection arg4);

  boolean bindService(Intent arg0, int arg1, Executor arg2, ServiceConnection arg3);

  boolean bindService(Intent arg0, ServiceConnection arg1, Context.BindServiceFlags arg2);

  boolean bindService(Intent arg0, Context.BindServiceFlags arg1, Executor arg2,
      ServiceConnection arg3);

  boolean bindService(Intent arg0, ServiceConnection arg1, int arg2);

  boolean bindServiceAsUser(Intent arg0, ServiceConnection arg1, int arg2, UserHandle arg3);

  boolean bindServiceAsUser(Intent arg0, ServiceConnection arg1, Context.BindServiceFlags arg2,
      UserHandle arg3);

  int checkCallingPermission(String arg0);

  int checkCallingUriPermission(Uri arg0, int arg1);

  int[] checkCallingUriPermissions(List<Uri> arg0, int arg1);

  int checkSelfPermission(String arg0);

  int checkUriPermission(Uri arg0, int arg1, int arg2, int arg3);

  int checkUriPermission(Uri arg0, String arg1, String arg2, int arg3, int arg4, int arg5);

  int[] checkUriPermissions(List<Uri> arg0, int arg1, int arg2, int arg3);

  void clearWallpaper() throws IOException;

  Context createAttributionContext(String arg0);

  Context createConfigurationContext(Configuration arg0);

  Context createContext(ContextParams arg0);

  Context createContextForSplit(String arg0) throws PackageManager.NameNotFoundException;

  Context createDeviceContext(int arg0);

  Context createDisplayContext(Display arg0);

  Context createPackageContext(String arg0, int arg1) throws PackageManager.NameNotFoundException;

  Context createWindowContext(Display arg0, int arg1, Bundle arg2);

  Context createWindowContext(int arg0, Bundle arg1);

  String[] databaseList();

  boolean deleteDatabase(String arg0);

  boolean deleteSharedPreferences(String arg0);

  void enforceCallingPermission(String arg0, String arg1);

  void enforcePermission(String arg0, int arg1, int arg2, String arg3);

  void enforceUriPermission(Uri arg0, String arg1, String arg2, int arg3, int arg4, int arg5,
      String arg6);

  void enforceUriPermission(Uri arg0, int arg1, int arg2, int arg3, String arg4);

  Context getApplicationContext();

  ApplicationInfo getApplicationInfo();

  AttributionSource getAttributionSource();

  String getAttributionTag();

  Context getBaseContext();

  File getCacheDir();

  File getCodeCacheDir();

  ContentResolver getContentResolver();

  File getDatabasePath(String arg0);

  int getDeviceId();

  File getExternalCacheDir();

  File[] getExternalCacheDirs();

  File getExternalFilesDir(String arg0);

  File[] getExternalFilesDirs(String arg0);

  File[] getExternalMediaDirs();

  File getFileStreamPath(String arg0);

  File getFilesDir();

  Executor getMainExecutor();

  Looper getMainLooper();

  File getNoBackupFilesDir();

  String getOpPackageName();

  String getPackageCodePath();

  PackageManager getPackageManager();

  String getPackageResourcePath();

  SharedPreferences getSharedPreferences(String arg0, int arg1);

  String getSystemServiceName(Class<?> arg0);

  Drawable getWallpaper();

  void grantUriPermission(String arg0, Uri arg1, int arg2);

  boolean isDeviceProtectedStorage();

  boolean isRestricted();

  boolean isUiContext();

  boolean moveDatabaseFrom(Context arg0, String arg1);

  boolean moveSharedPreferencesFrom(Context arg0, String arg1);

  FileInputStream openFileInput(String arg0) throws FileNotFoundException;

  FileOutputStream openFileOutput(String arg0, int arg1) throws FileNotFoundException;

  SQLiteDatabase openOrCreateDatabase(String arg0, int arg1, SQLiteDatabase.CursorFactory arg2);

  SQLiteDatabase openOrCreateDatabase(String arg0, int arg1, SQLiteDatabase.CursorFactory arg2,
      DatabaseErrorHandler arg3);

  Drawable peekWallpaper();

  Intent registerReceiver(BroadcastReceiver arg0, IntentFilter arg1, String arg2, Handler arg3,
      int arg4);

  Intent registerReceiver(BroadcastReceiver arg0, IntentFilter arg1, int arg2);

  Intent registerReceiver(BroadcastReceiver arg0, IntentFilter arg1, String arg2, Handler arg3);

  Intent registerReceiver(BroadcastReceiver arg0, IntentFilter arg1);

  void removeStickyBroadcast(Intent arg0);

  void revokeUriPermission(String arg0, Uri arg1, int arg2);

  void revokeUriPermission(Uri arg0, int arg1);

  void sendBroadcast(Intent arg0);

  void sendBroadcast(Intent arg0, String arg1);

  void sendBroadcast(Intent arg0, String arg1, Bundle arg2);

  void sendBroadcastAsUser(Intent arg0, UserHandle arg1);

  void sendBroadcastAsUser(Intent arg0, UserHandle arg1, String arg2);

  void sendOrderedBroadcast(Intent arg0, String arg1, BroadcastReceiver arg2, Handler arg3,
      int arg4, String arg5, Bundle arg6);

  void sendOrderedBroadcast(Intent arg0, String arg1);

  void sendOrderedBroadcast(Intent arg0, int arg1, String arg2, String arg3, BroadcastReceiver arg4,
      Handler arg5, String arg6, Bundle arg7, Bundle arg8);

  void sendOrderedBroadcast(Intent arg0, String arg1, Bundle arg2, BroadcastReceiver arg3,
      Handler arg4, int arg5, String arg6, Bundle arg7);

  void sendOrderedBroadcast(Intent arg0, String arg1, Bundle arg2);

  void sendOrderedBroadcast(Intent arg0, String arg1, String arg2, BroadcastReceiver arg3,
      Handler arg4, int arg5, String arg6, Bundle arg7);

  void sendOrderedBroadcastAsUser(Intent arg0, UserHandle arg1, String arg2, BroadcastReceiver arg3,
      Handler arg4, int arg5, String arg6, Bundle arg7);

  void sendStickyBroadcast(Intent arg0, Bundle arg1);

  void sendStickyBroadcast(Intent arg0);

  void sendStickyBroadcastAsUser(Intent arg0, UserHandle arg1);

  void sendStickyOrderedBroadcast(Intent arg0, BroadcastReceiver arg1, Handler arg2, int arg3,
      String arg4, Bundle arg5);

  void setWallpaper(Bitmap arg0) throws IOException;

  void setWallpaper(InputStream arg0) throws IOException;

  ComponentName startForegroundService(Intent arg0);

  boolean startInstrumentation(ComponentName arg0, String arg1, Bundle arg2);

  ComponentName startService(Intent arg0);

  boolean stopService(Intent arg0);

  void unbindService(ServiceConnection arg0);

  void unregisterReceiver(BroadcastReceiver arg0);

  void updateServiceGroup(ServiceConnection arg0, int arg1, int arg2);

  int checkPermission(String arg0, int arg1, int arg2);

  String getPackageName();

  void sendBroadcastWithMultiplePermissions(Intent arg0, String[] arg1);

  boolean bindIsolatedService(Intent arg0, Context.BindServiceFlags arg1, String arg2,
      Executor arg3, ServiceConnection arg4);

  void revokeSelfPermissionOnKill(String arg0);

  void attachBaseContext(Context arg0);

  boolean isChangingConfigurations();

  void finish();

  ClassLoader getClassLoader();

  LayoutInflater getLayoutInflater();

  Resources getResources();

  void recreate();

  ComponentName getCallingActivity();

  void onActionModeFinished(ActionMode arg0);

  void onActionModeStarted(ActionMode arg0);

  void onAttachedToWindow();

  void onContentChanged();

  boolean onCreatePanelMenu(int arg0, Menu arg1);

  View onCreatePanelView(int arg0);

  void onDetachedFromWindow();

  boolean onMenuItemSelected(int arg0, MenuItem arg1);

  boolean onMenuOpened(int arg0, Menu arg1);

  void onPanelClosed(int arg0, Menu arg1);

  boolean onPreparePanel(int arg0, View arg1, Menu arg2);

  void onProvideKeyboardShortcuts(List<KeyboardShortcutGroup> arg0, Menu arg1, int arg2);

  boolean onSearchRequested(SearchEvent arg0);

  boolean onSearchRequested();

  void onWindowAttributesChanged(WindowManager.LayoutParams arg0);

  void onWindowFocusChanged(boolean arg0);

  ActionMode onWindowStartingActionMode(ActionMode.Callback arg0, int arg1);

  ActionMode onWindowStartingActionMode(ActionMode.Callback arg0);

  void onPointerCaptureChanged(boolean arg0);

  boolean dispatchPopulateAccessibilityEvent(AccessibilityEvent arg0);

  boolean dispatchGenericMotionEvent(MotionEvent arg0);

  boolean dispatchKeyEvent(KeyEvent arg0);

  boolean dispatchKeyShortcutEvent(KeyEvent arg0);

  boolean dispatchTouchEvent(MotionEvent arg0);

  boolean dispatchTrackballEvent(MotionEvent arg0);

  boolean superIsChangingConfigurations();

  void superFinish();

  ClassLoader superGetClassLoader();

  LayoutInflater superGetLayoutInflater();

  Resources superGetResources();

  void superRecreate();

  ComponentName superGetCallingActivity();

  void superOnCreateNavigateUpTaskStack(TaskStackBuilder arg0);

  void superOnLocalVoiceInteractionStarted();

  void superOnLocalVoiceInteractionStopped();

  void superOnPictureInPictureModeChanged(boolean arg0, Configuration arg1);

  void superOnPictureInPictureModeChanged(boolean arg0);

  boolean superOnPictureInPictureRequested();

  void superOnPictureInPictureUiStateChanged(PictureInPictureUiState arg0);

  void superOnPrepareNavigateUpTaskStack(TaskStackBuilder arg0);

  Object superOnRetainNonConfigurationInstance();

  void superOnTopResumedActivityChanged(boolean arg0);

  void superOnCreate(Bundle arg0, PersistableBundle arg1);

  boolean superOnKeyDown(int arg0, KeyEvent arg1);

  boolean superOnKeyUp(int arg0, KeyEvent arg1);

  void superOnActionModeFinished(ActionMode arg0);

  void superOnActionModeStarted(ActionMode arg0);

  void superOnActivityReenter(int arg0, Intent arg1);

  void superOnActivityResult(int arg0, int arg1, Intent arg2, ComponentCaller arg3);

  void superOnAttachFragment(Fragment arg0);

  void superOnAttachedToWindow();

  void superOnBackPressed();

  void superOnConfigurationChanged(Configuration arg0);

  void superOnContentChanged();

  boolean superOnContextItemSelected(MenuItem arg0);

  void superOnContextMenuClosed(Menu arg0);

  void superOnCreateContextMenu(ContextMenu arg0, View arg1, ContextMenu.ContextMenuInfo arg2);

  CharSequence superOnCreateDescription();

  boolean superOnCreateOptionsMenu(Menu arg0);

  boolean superOnCreatePanelMenu(int arg0, Menu arg1);

  View superOnCreatePanelView(int arg0);

  boolean superOnCreateThumbnail(Bitmap arg0, Canvas arg1);

  View superOnCreateView(View arg0, String arg1, Context arg2, AttributeSet arg3);

  View superOnCreateView(String arg0, Context arg1, AttributeSet arg2);

  void superOnDetachedFromWindow();

  void superOnEnterAnimationComplete();

  boolean superOnGenericMotionEvent(MotionEvent arg0);

  void superOnGetDirectActions(CancellationSignal arg0, Consumer<List<DirectAction>> arg1);

  boolean superOnKeyLongPress(int arg0, KeyEvent arg1);

  boolean superOnKeyMultiple(int arg0, int arg1, KeyEvent arg2);

  boolean superOnKeyShortcut(int arg0, KeyEvent arg1);

  void superOnLowMemory();

  boolean superOnMenuItemSelected(int arg0, MenuItem arg1);

  boolean superOnMenuOpened(int arg0, Menu arg1);

  void superOnMultiWindowModeChanged(boolean arg0);

  void superOnMultiWindowModeChanged(boolean arg0, Configuration arg1);

  boolean superOnNavigateUp();

  boolean superOnNavigateUpFromChild(Activity arg0);

  void superOnNewIntent(Intent arg0, ComponentCaller arg1);

  boolean superOnOptionsItemSelected(MenuItem arg0);

  void superOnOptionsMenuClosed(Menu arg0);

  void superOnPanelClosed(int arg0, Menu arg1);

  void superOnPerformDirectAction(String arg0, Bundle arg1, CancellationSignal arg2,
      Consumer<Bundle> arg3);

  void superOnPostCreate(Bundle arg0, PersistableBundle arg1);

  boolean superOnPrepareOptionsMenu(Menu arg0);

  boolean superOnPreparePanel(int arg0, View arg1, Menu arg2);

  void superOnProvideAssistContent(AssistContent arg0);

  void superOnProvideAssistData(Bundle arg0);

  void superOnProvideKeyboardShortcuts(List<KeyboardShortcutGroup> arg0, Menu arg1, int arg2);

  Uri superOnProvideReferrer();

  void superOnRequestPermissionsResult(int arg0, String[] arg1, int[] arg2);

  void superOnRequestPermissionsResult(int arg0, String[] arg1, int[] arg2, int arg3);

  void superOnRestoreInstanceState(Bundle arg0, PersistableBundle arg1);

  void superOnSaveInstanceState(Bundle arg0, PersistableBundle arg1);

  boolean superOnSearchRequested(SearchEvent arg0);

  boolean superOnSearchRequested();

  void superOnStateNotSaved();

  boolean superOnTouchEvent(MotionEvent arg0);

  boolean superOnTrackballEvent(MotionEvent arg0);

  void superOnTrimMemory(int arg0);

  void superOnUserInteraction();

  void superOnVisibleBehindCanceled();

  void superOnWindowAttributesChanged(WindowManager.LayoutParams arg0);

  void superOnWindowFocusChanged(boolean arg0);

  ActionMode superOnWindowStartingActionMode(ActionMode.Callback arg0, int arg1);

  ActionMode superOnWindowStartingActionMode(ActionMode.Callback arg0);

  void superOnPointerCaptureChanged(boolean arg0);

  void superOnCreate(Bundle arg0);

  void superOnDestroy();

  void superOnPause();

  void superOnRestart();

  void superOnResume();

  void superOnStop();

  void superOnActivityResult(int arg0, int arg1, Intent arg2);

  void superOnApplyThemeResource(Resources.Theme arg0, int arg1, boolean arg2);

  void superOnChildTitleChanged(Activity arg0, CharSequence arg1);

  Dialog superOnCreateDialog(int arg0);

  Dialog superOnCreateDialog(int arg0, Bundle arg1);

  void superOnNewIntent(Intent arg0);

  void superOnPostCreate(Bundle arg0);

  void superOnPostResume();

  void superOnPrepareDialog(int arg0, Dialog arg1);

  void superOnPrepareDialog(int arg0, Dialog arg1, Bundle arg2);

  void superOnRestoreInstanceState(Bundle arg0);

  void superOnSaveInstanceState(Bundle arg0);

  void superOnTitleChanged(CharSequence arg0, int arg1);

  void superOnUserLeaveHint();

  void superOnStart();

  boolean superDispatchPopulateAccessibilityEvent(AccessibilityEvent arg0);

  boolean superDispatchGenericMotionEvent(MotionEvent arg0);

  boolean superDispatchKeyEvent(KeyEvent arg0);

  boolean superDispatchKeyShortcutEvent(KeyEvent arg0);

  boolean superDispatchTouchEvent(MotionEvent arg0);

  boolean superDispatchTrackballEvent(MotionEvent arg0);

  void superDump(String arg0, FileDescriptor arg1, PrintWriter arg2, String[] arg3);

  void superClearOverrideActivityTransition(int arg0);

  void superDismissKeyboardShortcutsHelper();

  TransitionManager superGetContentTransitionManager();

  Object superGetLastNonConfigurationInstance();

  int superGetMaxNumPictureInPictureActions();

  boolean superIsActivityTransitionRunning();

  boolean superIsLocalVoiceInteractionSupported();

  void superRegisterActivityLifecycleCallbacks(Application.ActivityLifecycleCallbacks arg0);

  void superRegisterScreenCaptureCallback(Executor arg0, Activity.ScreenCaptureCallback arg1);

  DragAndDropPermissions superRequestDragAndDropPermissions(DragEvent arg0);

  void superRequestOpenInBrowserEducation();

  void superRequestShowKeyboardShortcuts();

  void superSetAllowCrossUidActivitySwitchFromBelow(boolean arg0);

  void superSetContentTransitionManager(TransitionManager arg0);

  void superSetEnterSharedElementCallback(SharedElementCallback arg0);

  void superSetExitSharedElementCallback(SharedElementCallback arg0);

  void superSetProgressBarIndeterminate(boolean arg0);

  void superSetProgressBarIndeterminateVisibility(boolean arg0);

  void superSetRecentsScreenshotEnabled(boolean arg0);

  boolean superShouldShowRequestPermissionRationale(String arg0);

  boolean superShouldShowRequestPermissionRationale(String arg0, int arg1);

  void superStartPostponedEnterTransition();

  void superUnregisterActivityLifecycleCallbacks(Application.ActivityLifecycleCallbacks arg0);

  void superUnregisterComponentCallbacks(ComponentCallbacks arg0);

  void superUnregisterScreenCaptureCallback(Activity.ScreenCaptureCallback arg0);

  ComponentCaller superGetCaller();

  Intent superGetIntent();

  int superGetTaskId();

  CharSequence superGetTitle();

  Window superGetWindow();

  boolean superIsChild();

  boolean superIsTaskRoot();

  void superSetIntent(Intent arg0);

  void superSetIntent(Intent arg0, ComponentCaller arg1);

  void superSetTheme(int arg0);

  void superSetTitle(int arg0);

  void superSetTitle(CharSequence arg0);

  void superSetVisible(boolean arg0);

  boolean superShowAssist(Bundle arg0);

  boolean superShowDialog(int arg0, Bundle arg1);

  void superShowDialog(int arg0);

  void superAddContentView(View arg0, ViewGroup.LayoutParams arg1);

  void superCloseContextMenu();

  void superCloseOptionsMenu();

  PendingIntent superCreatePendingResult(int arg0, Intent arg1, int arg2);

  void superDismissDialog(int arg0);

  void superEnterPictureInPictureMode();

  boolean superEnterPictureInPictureMode(PictureInPictureParams arg0);

  <T extends View> T superFindViewById(int arg0);

  void superFinishActivity(int arg0);

  void superFinishActivityFromChild(Activity arg0, int arg1);

  void superFinishAffinity();

  void superFinishAfterTransition();

  void superFinishAndRemoveTask();

  void superFinishFromChild(Activity arg0);

  ActionBar superGetActionBar();

  Application superGetApplication();

  String superGetCallingPackage();

  int superGetChangingConfigurations();

  ComponentName superGetComponentName();

  Scene superGetContentScene();

  ComponentCaller superGetCurrentCaller();

  View superGetCurrentFocus();

  FragmentManager superGetFragmentManager();

  ComponentCaller superGetInitialCaller();

  String superGetLaunchedFromPackage();

  int superGetLaunchedFromUid();

  LoaderManager superGetLoaderManager();

  String superGetLocalClassName();

  MediaController superGetMediaController();

  MenuInflater superGetMenuInflater();

  OnBackInvokedDispatcher superGetOnBackInvokedDispatcher();

  Intent superGetParentActivityIntent();

  SharedPreferences superGetPreferences(int arg0);

  Uri superGetReferrer();

  int superGetRequestedOrientation();

  SearchEvent superGetSearchEvent();

  SplashScreen superGetSplashScreen();

  Object superGetSystemService(String arg0);

  int superGetTitleColor();

  VoiceInteractor superGetVoiceInteractor();

  int superGetVolumeControlStream();

  WindowManager superGetWindowManager();

  boolean superHasWindowFocus();

  void superInvalidateOptionsMenu();

  boolean superIsFinishing();

  boolean superIsImmersive();

  boolean superIsInMultiWindowMode();

  boolean superIsInPictureInPictureMode();

  boolean superIsLaunchedFromBubble();

  boolean superIsVoiceInteraction();

  boolean superIsVoiceInteractionRoot();

  Cursor superManagedQuery(Uri arg0, String[] arg1, String arg2, String[] arg3, String arg4);

  boolean superMoveTaskToBack(boolean arg0);

  boolean superNavigateUpTo(Intent arg0);

  boolean superNavigateUpToFromChild(Activity arg0, Intent arg1);

  void superOpenContextMenu(View arg0);

  void superOpenOptionsMenu();

  void superOverrideActivityTransition(int arg0, int arg1, int arg2, int arg3);

  void superOverrideActivityTransition(int arg0, int arg1, int arg2);

  void superOverridePendingTransition(int arg0, int arg1, int arg2);

  void superOverridePendingTransition(int arg0, int arg1);

  void superPostponeEnterTransition();

  void superRegisterComponentCallbacks(ComponentCallbacks arg0);

  void superRegisterForContextMenu(View arg0);

  boolean superReleaseInstance();

  void superRemoveDialog(int arg0);

  void superReportFullyDrawn();

  void superRequestFullscreenMode(int arg0, OutcomeReceiver<Void, Throwable> arg1);

  void superRequestPermissions(String[] arg0, int arg1);

  void superRequestPermissions(String[] arg0, int arg1, int arg2);

  boolean superRequestVisibleBehind(boolean arg0);

  boolean superRequestWindowFeature(int arg0);

  <T extends View> T superRequireViewById(int arg0);

  void superRunOnUiThread(Runnable arg0);

  void superSetActionBar(Toolbar arg0);

  void superSetContentView(int arg0);

  void superSetContentView(View arg0, ViewGroup.LayoutParams arg1);

  void superSetContentView(View arg0);

  void superSetDefaultKeyMode(int arg0);

  void superSetFeatureDrawable(int arg0, Drawable arg1);

  void superSetFeatureDrawableAlpha(int arg0, int arg1);

  void superSetFeatureDrawableResource(int arg0, int arg1);

  void superSetFeatureDrawableUri(int arg0, Uri arg1);

  void superSetFinishOnTouchOutside(boolean arg0);

  void superSetImmersive(boolean arg0);

  void superSetInheritShowWhenLocked(boolean arg0);

  void superSetLocusContext(LocusId arg0, Bundle arg1);

  void superSetMediaController(MediaController arg0);

  void superSetPictureInPictureParams(PictureInPictureParams arg0);

  void superSetProgress(int arg0);

  void superSetProgressBarVisibility(boolean arg0);

  void superSetRequestedOrientation(int arg0);

  void superSetSecondaryProgress(int arg0);

  void superSetShouldDockBigOverlays(boolean arg0);

  boolean superShouldDockBigOverlays();

  void superSetShowWhenLocked(boolean arg0);

  void superSetTaskDescription(ActivityManager.TaskDescription arg0);

  void superSetTitleColor(int arg0);

  boolean superSetTranslucent(boolean arg0);

  void superSetTurnScreenOn(boolean arg0);

  void superSetVolumeControlStream(int arg0);

  void superSetVrModeEnabled(boolean arg0, ComponentName arg1) throws
      PackageManager.NameNotFoundException;

  boolean superShouldUpRecreateTask(Intent arg0);

  void superShowLockTaskEscapeMessage();

  ActionMode superStartActionMode(ActionMode.Callback arg0);

  ActionMode superStartActionMode(ActionMode.Callback arg0, int arg1);

  void superStartActivities(Intent[] arg0, Bundle arg1);

  void superStartActivities(Intent[] arg0);

  void superStartActivity(Intent arg0, Bundle arg1);

  void superStartActivity(Intent arg0);

  void superStartActivityForResult(Intent arg0, int arg1);

  void superStartActivityForResult(Intent arg0, int arg1, Bundle arg2);

  void superStartActivityFromChild(Activity arg0, Intent arg1, int arg2, Bundle arg3);

  void superStartActivityFromChild(Activity arg0, Intent arg1, int arg2);

  void superStartActivityFromFragment(Fragment arg0, Intent arg1, int arg2, Bundle arg3);

  void superStartActivityFromFragment(Fragment arg0, Intent arg1, int arg2);

  boolean superStartActivityIfNeeded(Intent arg0, int arg1);

  boolean superStartActivityIfNeeded(Intent arg0, int arg1, Bundle arg2);

  void superStartIntentSender(IntentSender arg0, Intent arg1, int arg2, int arg3, int arg4) throws
      IntentSender.SendIntentException;

  void superStartIntentSender(IntentSender arg0, Intent arg1, int arg2, int arg3, int arg4,
      Bundle arg5) throws IntentSender.SendIntentException;

  void superStartIntentSenderForResult(IntentSender arg0, int arg1, Intent arg2, int arg3, int arg4,
      int arg5, Bundle arg6) throws IntentSender.SendIntentException;

  void superStartIntentSenderForResult(IntentSender arg0, int arg1, Intent arg2, int arg3, int arg4,
      int arg5) throws IntentSender.SendIntentException;

  void superStartIntentSenderFromChild(Activity arg0, IntentSender arg1, int arg2, Intent arg3,
      int arg4, int arg5, int arg6, Bundle arg7) throws IntentSender.SendIntentException;

  void superStartIntentSenderFromChild(Activity arg0, IntentSender arg1, int arg2, Intent arg3,
      int arg4, int arg5, int arg6) throws IntentSender.SendIntentException;

  void superStartLocalVoiceInteraction(Bundle arg0);

  void superStartLockTask();

  void superStartManagingCursor(Cursor arg0);

  boolean superStartNextMatchingActivity(Intent arg0);

  boolean superStartNextMatchingActivity(Intent arg0, Bundle arg1);

  void superStartSearch(String arg0, boolean arg1, Bundle arg2, boolean arg3);

  void superStopLocalVoiceInteraction();

  void superStopLockTask();

  void superStopManagingCursor(Cursor arg0);

  void superTakeKeyEvents(boolean arg0);

  void superTriggerSearch(String arg0, Bundle arg1);

  void superUnregisterForContextMenu(View arg0);

  Activity superGetParent();

  boolean superIsDestroyed();

  void superSetResult(int arg0, Intent arg1);

  void superSetResult(int arg0);

  void superSetTheme(Resources.Theme arg0);

  AssetManager superGetAssets();

  Resources.Theme superGetTheme();

  void superApplyOverrideConfiguration(Configuration arg0);

  int superCheckCallingOrSelfPermission(String arg0);

  int superCheckCallingOrSelfUriPermission(Uri arg0, int arg1);

  int[] superCheckCallingOrSelfUriPermissions(List<Uri> arg0, int arg1);

  int superCheckContentUriPermissionFull(Uri arg0, int arg1, int arg2, int arg3);

  Context superCreateDeviceProtectedStorageContext();

  void superEnforceCallingOrSelfPermission(String arg0, String arg1);

  void superEnforceCallingOrSelfUriPermission(Uri arg0, int arg1, String arg2);

  void superEnforceCallingUriPermission(Uri arg0, int arg1, String arg2);

  int superGetWallpaperDesiredMinimumHeight();

  int superGetWallpaperDesiredMinimumWidth();

  void superRegisterDeviceIdChangeListener(Executor arg0, IntConsumer arg1);

  void superRemoveStickyBroadcastAsUser(Intent arg0, UserHandle arg1);

  void superRevokeSelfPermissionsOnKill(Collection<String> arg0);

  void superSendStickyOrderedBroadcastAsUser(Intent arg0, UserHandle arg1, BroadcastReceiver arg2,
      Handler arg3, int arg4, String arg5, Bundle arg6);

  void superUnregisterDeviceIdChangeListener(IntConsumer arg0);

  boolean superDeleteFile(String arg0);

  String[] superFileList();

  File superGetDataDir();

  File superGetDir(String arg0, int arg1);

  Display superGetDisplay();

  File superGetObbDir();

  File[] superGetObbDirs();

  ContextParams superGetParams();

  boolean superBindIsolatedService(Intent arg0, int arg1, String arg2, Executor arg3,
      ServiceConnection arg4);

  boolean superBindService(Intent arg0, int arg1, Executor arg2, ServiceConnection arg3);

  boolean superBindService(Intent arg0, ServiceConnection arg1, Context.BindServiceFlags arg2);

  boolean superBindService(Intent arg0, Context.BindServiceFlags arg1, Executor arg2,
      ServiceConnection arg3);

  boolean superBindService(Intent arg0, ServiceConnection arg1, int arg2);

  boolean superBindServiceAsUser(Intent arg0, ServiceConnection arg1, int arg2, UserHandle arg3);

  boolean superBindServiceAsUser(Intent arg0, ServiceConnection arg1, Context.BindServiceFlags arg2,
      UserHandle arg3);

  int superCheckCallingPermission(String arg0);

  int superCheckCallingUriPermission(Uri arg0, int arg1);

  int[] superCheckCallingUriPermissions(List<Uri> arg0, int arg1);

  int superCheckSelfPermission(String arg0);

  int superCheckUriPermission(Uri arg0, int arg1, int arg2, int arg3);

  int superCheckUriPermission(Uri arg0, String arg1, String arg2, int arg3, int arg4, int arg5);

  int[] superCheckUriPermissions(List<Uri> arg0, int arg1, int arg2, int arg3);

  void superClearWallpaper() throws IOException;

  Context superCreateAttributionContext(String arg0);

  Context superCreateConfigurationContext(Configuration arg0);

  Context superCreateContext(ContextParams arg0);

  Context superCreateContextForSplit(String arg0) throws PackageManager.NameNotFoundException;

  Context superCreateDeviceContext(int arg0);

  Context superCreateDisplayContext(Display arg0);

  Context superCreatePackageContext(String arg0, int arg1) throws
      PackageManager.NameNotFoundException;

  Context superCreateWindowContext(Display arg0, int arg1, Bundle arg2);

  Context superCreateWindowContext(int arg0, Bundle arg1);

  String[] superDatabaseList();

  boolean superDeleteDatabase(String arg0);

  boolean superDeleteSharedPreferences(String arg0);

  void superEnforceCallingPermission(String arg0, String arg1);

  void superEnforcePermission(String arg0, int arg1, int arg2, String arg3);

  void superEnforceUriPermission(Uri arg0, String arg1, String arg2, int arg3, int arg4, int arg5,
      String arg6);

  void superEnforceUriPermission(Uri arg0, int arg1, int arg2, int arg3, String arg4);

  Context superGetApplicationContext();

  ApplicationInfo superGetApplicationInfo();

  AttributionSource superGetAttributionSource();

  String superGetAttributionTag();

  Context superGetBaseContext();

  File superGetCacheDir();

  File superGetCodeCacheDir();

  ContentResolver superGetContentResolver();

  File superGetDatabasePath(String arg0);

  int superGetDeviceId();

  File superGetExternalCacheDir();

  File[] superGetExternalCacheDirs();

  File superGetExternalFilesDir(String arg0);

  File[] superGetExternalFilesDirs(String arg0);

  File[] superGetExternalMediaDirs();

  File superGetFileStreamPath(String arg0);

  File superGetFilesDir();

  Executor superGetMainExecutor();

  Looper superGetMainLooper();

  File superGetNoBackupFilesDir();

  String superGetOpPackageName();

  String superGetPackageCodePath();

  PackageManager superGetPackageManager();

  String superGetPackageResourcePath();

  SharedPreferences superGetSharedPreferences(String arg0, int arg1);

  String superGetSystemServiceName(Class<?> arg0);

  Drawable superGetWallpaper();

  void superGrantUriPermission(String arg0, Uri arg1, int arg2);

  boolean superIsDeviceProtectedStorage();

  boolean superIsRestricted();

  boolean superIsUiContext();

  boolean superMoveDatabaseFrom(Context arg0, String arg1);

  boolean superMoveSharedPreferencesFrom(Context arg0, String arg1);

  FileInputStream superOpenFileInput(String arg0) throws FileNotFoundException;

  FileOutputStream superOpenFileOutput(String arg0, int arg1) throws FileNotFoundException;

  SQLiteDatabase superOpenOrCreateDatabase(String arg0, int arg1,
      SQLiteDatabase.CursorFactory arg2);

  SQLiteDatabase superOpenOrCreateDatabase(String arg0, int arg1, SQLiteDatabase.CursorFactory arg2,
      DatabaseErrorHandler arg3);

  Drawable superPeekWallpaper();

  Intent superRegisterReceiver(BroadcastReceiver arg0, IntentFilter arg1, String arg2, Handler arg3,
      int arg4);

  Intent superRegisterReceiver(BroadcastReceiver arg0, IntentFilter arg1, int arg2);

  Intent superRegisterReceiver(BroadcastReceiver arg0, IntentFilter arg1, String arg2,
      Handler arg3);

  Intent superRegisterReceiver(BroadcastReceiver arg0, IntentFilter arg1);

  void superRemoveStickyBroadcast(Intent arg0);

  void superRevokeUriPermission(String arg0, Uri arg1, int arg2);

  void superRevokeUriPermission(Uri arg0, int arg1);

  void superSendBroadcast(Intent arg0);

  void superSendBroadcast(Intent arg0, String arg1);

  void superSendBroadcast(Intent arg0, String arg1, Bundle arg2);

  void superSendBroadcastAsUser(Intent arg0, UserHandle arg1);

  void superSendBroadcastAsUser(Intent arg0, UserHandle arg1, String arg2);

  void superSendOrderedBroadcast(Intent arg0, String arg1, BroadcastReceiver arg2, Handler arg3,
      int arg4, String arg5, Bundle arg6);

  void superSendOrderedBroadcast(Intent arg0, String arg1);

  void superSendOrderedBroadcast(Intent arg0, int arg1, String arg2, String arg3,
      BroadcastReceiver arg4, Handler arg5, String arg6, Bundle arg7, Bundle arg8);

  void superSendOrderedBroadcast(Intent arg0, String arg1, Bundle arg2, BroadcastReceiver arg3,
      Handler arg4, int arg5, String arg6, Bundle arg7);

  void superSendOrderedBroadcast(Intent arg0, String arg1, Bundle arg2);

  void superSendOrderedBroadcast(Intent arg0, String arg1, String arg2, BroadcastReceiver arg3,
      Handler arg4, int arg5, String arg6, Bundle arg7);

  void superSendOrderedBroadcastAsUser(Intent arg0, UserHandle arg1, String arg2,
      BroadcastReceiver arg3, Handler arg4, int arg5, String arg6, Bundle arg7);

  void superSendStickyBroadcast(Intent arg0, Bundle arg1);

  void superSendStickyBroadcast(Intent arg0);

  void superSendStickyBroadcastAsUser(Intent arg0, UserHandle arg1);

  void superSendStickyOrderedBroadcast(Intent arg0, BroadcastReceiver arg1, Handler arg2, int arg3,
      String arg4, Bundle arg5);

  void superSetWallpaper(Bitmap arg0) throws IOException;

  void superSetWallpaper(InputStream arg0) throws IOException;

  ComponentName superStartForegroundService(Intent arg0);

  boolean superStartInstrumentation(ComponentName arg0, String arg1, Bundle arg2);

  ComponentName superStartService(Intent arg0);

  boolean superStopService(Intent arg0);

  void superUnbindService(ServiceConnection arg0);

  void superUnregisterReceiver(BroadcastReceiver arg0);

  void superUpdateServiceGroup(ServiceConnection arg0, int arg1, int arg2);

  int superCheckPermission(String arg0, int arg1, int arg2);

  String superGetPackageName();

  void superSendBroadcastWithMultiplePermissions(Intent arg0, String[] arg1);

  boolean superBindIsolatedService(Intent arg0, Context.BindServiceFlags arg1, String arg2,
      Executor arg3, ServiceConnection arg4);

  void superRevokeSelfPermissionOnKill(String arg0);

  void superAttachBaseContext(Context arg0);
}
