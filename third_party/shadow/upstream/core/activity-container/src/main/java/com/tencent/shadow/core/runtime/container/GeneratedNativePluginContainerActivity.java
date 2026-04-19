package com.tencent.shadow.core.runtime.container;

import android.annotation.SuppressLint;
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
import android.app.NativeActivity;
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
import java.lang.Override;
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
 */
@SuppressLint({"NewApi", "MissingPermission"})
abstract class GeneratedNativePluginContainerActivity extends NativeActivity implements GeneratedHostActivityDelegator {
  GeneratedHostActivityDelegate hostActivityDelegate;

  @Override
  public boolean isChangingConfigurations() {
    if (hostActivityDelegate != null) {
      return hostActivityDelegate.isChangingConfigurations();
    } else {
      return super.isChangingConfigurations();
    }
  }

  @Override
  public void finish() {
    if (hostActivityDelegate != null) {
      hostActivityDelegate.finish();
    } else {
      super.finish();
    }
  }

  @Override
  public ClassLoader getClassLoader() {
    if (hostActivityDelegate != null) {
      return hostActivityDelegate.getClassLoader();
    } else {
      return super.getClassLoader();
    }
  }

  @Override
  public LayoutInflater getLayoutInflater() {
    if (hostActivityDelegate != null) {
      return hostActivityDelegate.getLayoutInflater();
    } else {
      return super.getLayoutInflater();
    }
  }

  @Override
  public Resources getResources() {
    if (hostActivityDelegate != null) {
      return hostActivityDelegate.getResources();
    } else {
      return super.getResources();
    }
  }

  @Override
  public void recreate() {
    if (hostActivityDelegate != null) {
      hostActivityDelegate.recreate();
    } else {
      super.recreate();
    }
  }

  @Override
  public ComponentName getCallingActivity() {
    if (hostActivityDelegate != null) {
      return hostActivityDelegate.getCallingActivity();
    } else {
      return super.getCallingActivity();
    }
  }

  @Override
  public void onCreateNavigateUpTaskStack(TaskStackBuilder arg0) {
    if (hostActivityDelegate != null) {
      hostActivityDelegate.onCreateNavigateUpTaskStack(arg0);
    } else {
      super.onCreateNavigateUpTaskStack(arg0);
    }
  }

  @Override
  public void onLocalVoiceInteractionStarted() {
    if (hostActivityDelegate != null) {
      hostActivityDelegate.onLocalVoiceInteractionStarted();
    } else {
      super.onLocalVoiceInteractionStarted();
    }
  }

  @Override
  public void onLocalVoiceInteractionStopped() {
    if (hostActivityDelegate != null) {
      hostActivityDelegate.onLocalVoiceInteractionStopped();
    } else {
      super.onLocalVoiceInteractionStopped();
    }
  }

  @Override
  public void onPictureInPictureModeChanged(boolean arg0, Configuration arg1) {
    if (hostActivityDelegate != null) {
      hostActivityDelegate.onPictureInPictureModeChanged(arg0, arg1);
    } else {
      super.onPictureInPictureModeChanged(arg0, arg1);
    }
  }

  @Override
  public void onPictureInPictureModeChanged(boolean arg0) {
    if (hostActivityDelegate != null) {
      hostActivityDelegate.onPictureInPictureModeChanged(arg0);
    } else {
      super.onPictureInPictureModeChanged(arg0);
    }
  }

  @Override
  public boolean onPictureInPictureRequested() {
    if (hostActivityDelegate != null) {
      return hostActivityDelegate.onPictureInPictureRequested();
    } else {
      return super.onPictureInPictureRequested();
    }
  }

  @Override
  public void onPictureInPictureUiStateChanged(PictureInPictureUiState arg0) {
    if (hostActivityDelegate != null) {
      hostActivityDelegate.onPictureInPictureUiStateChanged(arg0);
    } else {
      super.onPictureInPictureUiStateChanged(arg0);
    }
  }

  @Override
  public void onPrepareNavigateUpTaskStack(TaskStackBuilder arg0) {
    if (hostActivityDelegate != null) {
      hostActivityDelegate.onPrepareNavigateUpTaskStack(arg0);
    } else {
      super.onPrepareNavigateUpTaskStack(arg0);
    }
  }

  @Override
  public Object onRetainNonConfigurationInstance() {
    if (hostActivityDelegate != null) {
      return hostActivityDelegate.onRetainNonConfigurationInstance();
    } else {
      return super.onRetainNonConfigurationInstance();
    }
  }

  @Override
  public void onTopResumedActivityChanged(boolean arg0) {
    if (hostActivityDelegate != null) {
      hostActivityDelegate.onTopResumedActivityChanged(arg0);
    } else {
      super.onTopResumedActivityChanged(arg0);
    }
  }

  @Override
  public void onCreate(Bundle arg0, PersistableBundle arg1) {
    if (hostActivityDelegate != null) {
      hostActivityDelegate.onCreate(arg0, arg1);
    } else {
      super.onCreate(arg0, arg1);
    }
  }

  @Override
  public boolean onKeyDown(int arg0, KeyEvent arg1) {
    if (hostActivityDelegate != null) {
      return hostActivityDelegate.onKeyDown(arg0, arg1);
    } else {
      return super.onKeyDown(arg0, arg1);
    }
  }

  @Override
  public boolean onKeyUp(int arg0, KeyEvent arg1) {
    if (hostActivityDelegate != null) {
      return hostActivityDelegate.onKeyUp(arg0, arg1);
    } else {
      return super.onKeyUp(arg0, arg1);
    }
  }

  @Override
  public void onActionModeFinished(ActionMode arg0) {
    if (hostActivityDelegate != null) {
      hostActivityDelegate.onActionModeFinished(arg0);
    } else {
      super.onActionModeFinished(arg0);
    }
  }

  @Override
  public void onActionModeStarted(ActionMode arg0) {
    if (hostActivityDelegate != null) {
      hostActivityDelegate.onActionModeStarted(arg0);
    } else {
      super.onActionModeStarted(arg0);
    }
  }

  @Override
  public void onActivityReenter(int arg0, Intent arg1) {
    if (hostActivityDelegate != null) {
      hostActivityDelegate.onActivityReenter(arg0, arg1);
    } else {
      super.onActivityReenter(arg0, arg1);
    }
  }

  @Override
  public void onActivityResult(int arg0, int arg1, Intent arg2, ComponentCaller arg3) {
    if (hostActivityDelegate != null) {
      hostActivityDelegate.onActivityResult(arg0, arg1, arg2, arg3);
    } else {
      super.onActivityResult(arg0, arg1, arg2, arg3);
    }
  }

  @Override
  public void onAttachFragment(Fragment arg0) {
    if (hostActivityDelegate != null) {
      hostActivityDelegate.onAttachFragment(arg0);
    } else {
      super.onAttachFragment(arg0);
    }
  }

  @Override
  public void onAttachedToWindow() {
    if (hostActivityDelegate != null) {
      hostActivityDelegate.onAttachedToWindow();
    } else {
      super.onAttachedToWindow();
    }
  }

  @Override
  public void onBackPressed() {
    if (hostActivityDelegate != null) {
      hostActivityDelegate.onBackPressed();
    } else {
      super.onBackPressed();
    }
  }

  @Override
  public void onConfigurationChanged(Configuration arg0) {
    if (hostActivityDelegate != null) {
      hostActivityDelegate.onConfigurationChanged(arg0);
    } else {
      super.onConfigurationChanged(arg0);
    }
  }

  @Override
  public void onContentChanged() {
    if (hostActivityDelegate != null) {
      hostActivityDelegate.onContentChanged();
    } else {
      super.onContentChanged();
    }
  }

  @Override
  public boolean onContextItemSelected(MenuItem arg0) {
    if (hostActivityDelegate != null) {
      return hostActivityDelegate.onContextItemSelected(arg0);
    } else {
      return super.onContextItemSelected(arg0);
    }
  }

  @Override
  public void onContextMenuClosed(Menu arg0) {
    if (hostActivityDelegate != null) {
      hostActivityDelegate.onContextMenuClosed(arg0);
    } else {
      super.onContextMenuClosed(arg0);
    }
  }

  @Override
  public void onCreateContextMenu(ContextMenu arg0, View arg1, ContextMenu.ContextMenuInfo arg2) {
    if (hostActivityDelegate != null) {
      hostActivityDelegate.onCreateContextMenu(arg0, arg1, arg2);
    } else {
      super.onCreateContextMenu(arg0, arg1, arg2);
    }
  }

  @Override
  public CharSequence onCreateDescription() {
    if (hostActivityDelegate != null) {
      return hostActivityDelegate.onCreateDescription();
    } else {
      return super.onCreateDescription();
    }
  }

  @Override
  public boolean onCreateOptionsMenu(Menu arg0) {
    if (hostActivityDelegate != null) {
      return hostActivityDelegate.onCreateOptionsMenu(arg0);
    } else {
      return super.onCreateOptionsMenu(arg0);
    }
  }

  @Override
  public boolean onCreatePanelMenu(int arg0, Menu arg1) {
    if (hostActivityDelegate != null) {
      return hostActivityDelegate.onCreatePanelMenu(arg0, arg1);
    } else {
      return super.onCreatePanelMenu(arg0, arg1);
    }
  }

  @Override
  public View onCreatePanelView(int arg0) {
    if (hostActivityDelegate != null) {
      return hostActivityDelegate.onCreatePanelView(arg0);
    } else {
      return super.onCreatePanelView(arg0);
    }
  }

  @Override
  public boolean onCreateThumbnail(Bitmap arg0, Canvas arg1) {
    if (hostActivityDelegate != null) {
      return hostActivityDelegate.onCreateThumbnail(arg0, arg1);
    } else {
      return super.onCreateThumbnail(arg0, arg1);
    }
  }

  @Override
  public View onCreateView(View arg0, String arg1, Context arg2, AttributeSet arg3) {
    if (hostActivityDelegate != null) {
      return hostActivityDelegate.onCreateView(arg0, arg1, arg2, arg3);
    } else {
      return super.onCreateView(arg0, arg1, arg2, arg3);
    }
  }

  @Override
  public View onCreateView(String arg0, Context arg1, AttributeSet arg2) {
    if (hostActivityDelegate != null) {
      return hostActivityDelegate.onCreateView(arg0, arg1, arg2);
    } else {
      return super.onCreateView(arg0, arg1, arg2);
    }
  }

  @Override
  public void onDetachedFromWindow() {
    if (hostActivityDelegate != null) {
      hostActivityDelegate.onDetachedFromWindow();
    } else {
      super.onDetachedFromWindow();
    }
  }

  @Override
  public void onEnterAnimationComplete() {
    if (hostActivityDelegate != null) {
      hostActivityDelegate.onEnterAnimationComplete();
    } else {
      super.onEnterAnimationComplete();
    }
  }

  @Override
  public boolean onGenericMotionEvent(MotionEvent arg0) {
    if (hostActivityDelegate != null) {
      return hostActivityDelegate.onGenericMotionEvent(arg0);
    } else {
      return super.onGenericMotionEvent(arg0);
    }
  }

  @Override
  public void onGetDirectActions(CancellationSignal arg0, Consumer<List<DirectAction>> arg1) {
    if (hostActivityDelegate != null) {
      hostActivityDelegate.onGetDirectActions(arg0, arg1);
    } else {
      super.onGetDirectActions(arg0, arg1);
    }
  }

  @Override
  public boolean onKeyLongPress(int arg0, KeyEvent arg1) {
    if (hostActivityDelegate != null) {
      return hostActivityDelegate.onKeyLongPress(arg0, arg1);
    } else {
      return super.onKeyLongPress(arg0, arg1);
    }
  }

  @Override
  public boolean onKeyMultiple(int arg0, int arg1, KeyEvent arg2) {
    if (hostActivityDelegate != null) {
      return hostActivityDelegate.onKeyMultiple(arg0, arg1, arg2);
    } else {
      return super.onKeyMultiple(arg0, arg1, arg2);
    }
  }

  @Override
  public boolean onKeyShortcut(int arg0, KeyEvent arg1) {
    if (hostActivityDelegate != null) {
      return hostActivityDelegate.onKeyShortcut(arg0, arg1);
    } else {
      return super.onKeyShortcut(arg0, arg1);
    }
  }

  @Override
  public void onLowMemory() {
    if (hostActivityDelegate != null) {
      hostActivityDelegate.onLowMemory();
    } else {
      super.onLowMemory();
    }
  }

  @Override
  public boolean onMenuItemSelected(int arg0, MenuItem arg1) {
    if (hostActivityDelegate != null) {
      return hostActivityDelegate.onMenuItemSelected(arg0, arg1);
    } else {
      return super.onMenuItemSelected(arg0, arg1);
    }
  }

  @Override
  public boolean onMenuOpened(int arg0, Menu arg1) {
    if (hostActivityDelegate != null) {
      return hostActivityDelegate.onMenuOpened(arg0, arg1);
    } else {
      return super.onMenuOpened(arg0, arg1);
    }
  }

  @Override
  public void onMultiWindowModeChanged(boolean arg0) {
    if (hostActivityDelegate != null) {
      hostActivityDelegate.onMultiWindowModeChanged(arg0);
    } else {
      super.onMultiWindowModeChanged(arg0);
    }
  }

  @Override
  public void onMultiWindowModeChanged(boolean arg0, Configuration arg1) {
    if (hostActivityDelegate != null) {
      hostActivityDelegate.onMultiWindowModeChanged(arg0, arg1);
    } else {
      super.onMultiWindowModeChanged(arg0, arg1);
    }
  }

  @Override
  public boolean onNavigateUp() {
    if (hostActivityDelegate != null) {
      return hostActivityDelegate.onNavigateUp();
    } else {
      return super.onNavigateUp();
    }
  }

  @Override
  public boolean onNavigateUpFromChild(Activity arg0) {
    if (hostActivityDelegate != null) {
      return hostActivityDelegate.onNavigateUpFromChild(arg0);
    } else {
      return super.onNavigateUpFromChild(arg0);
    }
  }

  @Override
  public void onNewIntent(Intent arg0, ComponentCaller arg1) {
    if (hostActivityDelegate != null) {
      hostActivityDelegate.onNewIntent(arg0, arg1);
    } else {
      super.onNewIntent(arg0, arg1);
    }
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem arg0) {
    if (hostActivityDelegate != null) {
      return hostActivityDelegate.onOptionsItemSelected(arg0);
    } else {
      return super.onOptionsItemSelected(arg0);
    }
  }

  @Override
  public void onOptionsMenuClosed(Menu arg0) {
    if (hostActivityDelegate != null) {
      hostActivityDelegate.onOptionsMenuClosed(arg0);
    } else {
      super.onOptionsMenuClosed(arg0);
    }
  }

  @Override
  public void onPanelClosed(int arg0, Menu arg1) {
    if (hostActivityDelegate != null) {
      hostActivityDelegate.onPanelClosed(arg0, arg1);
    } else {
      super.onPanelClosed(arg0, arg1);
    }
  }

  @Override
  public void onPerformDirectAction(String arg0, Bundle arg1, CancellationSignal arg2,
      Consumer<Bundle> arg3) {
    if (hostActivityDelegate != null) {
      hostActivityDelegate.onPerformDirectAction(arg0, arg1, arg2, arg3);
    } else {
      super.onPerformDirectAction(arg0, arg1, arg2, arg3);
    }
  }

  @Override
  public void onPostCreate(Bundle arg0, PersistableBundle arg1) {
    if (hostActivityDelegate != null) {
      hostActivityDelegate.onPostCreate(arg0, arg1);
    } else {
      super.onPostCreate(arg0, arg1);
    }
  }

  @Override
  public boolean onPrepareOptionsMenu(Menu arg0) {
    if (hostActivityDelegate != null) {
      return hostActivityDelegate.onPrepareOptionsMenu(arg0);
    } else {
      return super.onPrepareOptionsMenu(arg0);
    }
  }

  @Override
  public boolean onPreparePanel(int arg0, View arg1, Menu arg2) {
    if (hostActivityDelegate != null) {
      return hostActivityDelegate.onPreparePanel(arg0, arg1, arg2);
    } else {
      return super.onPreparePanel(arg0, arg1, arg2);
    }
  }

  @Override
  public void onProvideAssistContent(AssistContent arg0) {
    if (hostActivityDelegate != null) {
      hostActivityDelegate.onProvideAssistContent(arg0);
    } else {
      super.onProvideAssistContent(arg0);
    }
  }

  @Override
  public void onProvideAssistData(Bundle arg0) {
    if (hostActivityDelegate != null) {
      hostActivityDelegate.onProvideAssistData(arg0);
    } else {
      super.onProvideAssistData(arg0);
    }
  }

  @Override
  public void onProvideKeyboardShortcuts(List<KeyboardShortcutGroup> arg0, Menu arg1, int arg2) {
    if (hostActivityDelegate != null) {
      hostActivityDelegate.onProvideKeyboardShortcuts(arg0, arg1, arg2);
    } else {
      super.onProvideKeyboardShortcuts(arg0, arg1, arg2);
    }
  }

  @Override
  public Uri onProvideReferrer() {
    if (hostActivityDelegate != null) {
      return hostActivityDelegate.onProvideReferrer();
    } else {
      return super.onProvideReferrer();
    }
  }

  @Override
  public void onRequestPermissionsResult(int arg0, String[] arg1, int[] arg2) {
    if (hostActivityDelegate != null) {
      hostActivityDelegate.onRequestPermissionsResult(arg0, arg1, arg2);
    } else {
      super.onRequestPermissionsResult(arg0, arg1, arg2);
    }
  }

  @Override
  public void onRequestPermissionsResult(int arg0, String[] arg1, int[] arg2, int arg3) {
    if (hostActivityDelegate != null) {
      hostActivityDelegate.onRequestPermissionsResult(arg0, arg1, arg2, arg3);
    } else {
      super.onRequestPermissionsResult(arg0, arg1, arg2, arg3);
    }
  }

  @Override
  public void onRestoreInstanceState(Bundle arg0, PersistableBundle arg1) {
    if (hostActivityDelegate != null) {
      hostActivityDelegate.onRestoreInstanceState(arg0, arg1);
    } else {
      super.onRestoreInstanceState(arg0, arg1);
    }
  }

  @Override
  public void onSaveInstanceState(Bundle arg0, PersistableBundle arg1) {
    if (hostActivityDelegate != null) {
      hostActivityDelegate.onSaveInstanceState(arg0, arg1);
    } else {
      super.onSaveInstanceState(arg0, arg1);
    }
  }

  @Override
  public boolean onSearchRequested(SearchEvent arg0) {
    if (hostActivityDelegate != null) {
      return hostActivityDelegate.onSearchRequested(arg0);
    } else {
      return super.onSearchRequested(arg0);
    }
  }

  @Override
  public boolean onSearchRequested() {
    if (hostActivityDelegate != null) {
      return hostActivityDelegate.onSearchRequested();
    } else {
      return super.onSearchRequested();
    }
  }

  @Override
  public void onStateNotSaved() {
    if (hostActivityDelegate != null) {
      hostActivityDelegate.onStateNotSaved();
    } else {
      super.onStateNotSaved();
    }
  }

  @Override
  public boolean onTouchEvent(MotionEvent arg0) {
    if (hostActivityDelegate != null) {
      return hostActivityDelegate.onTouchEvent(arg0);
    } else {
      return super.onTouchEvent(arg0);
    }
  }

  @Override
  public boolean onTrackballEvent(MotionEvent arg0) {
    if (hostActivityDelegate != null) {
      return hostActivityDelegate.onTrackballEvent(arg0);
    } else {
      return super.onTrackballEvent(arg0);
    }
  }

  @Override
  public void onTrimMemory(int arg0) {
    if (hostActivityDelegate != null) {
      hostActivityDelegate.onTrimMemory(arg0);
    } else {
      super.onTrimMemory(arg0);
    }
  }

  @Override
  public void onUserInteraction() {
    if (hostActivityDelegate != null) {
      hostActivityDelegate.onUserInteraction();
    } else {
      super.onUserInteraction();
    }
  }

  @Override
  public void onVisibleBehindCanceled() {
    if (hostActivityDelegate != null) {
      hostActivityDelegate.onVisibleBehindCanceled();
    } else {
      super.onVisibleBehindCanceled();
    }
  }

  @Override
  public void onWindowAttributesChanged(WindowManager.LayoutParams arg0) {
    if (hostActivityDelegate != null) {
      hostActivityDelegate.onWindowAttributesChanged(arg0);
    } else {
      super.onWindowAttributesChanged(arg0);
    }
  }

  @Override
  public void onWindowFocusChanged(boolean arg0) {
    if (hostActivityDelegate != null) {
      hostActivityDelegate.onWindowFocusChanged(arg0);
    } else {
      super.onWindowFocusChanged(arg0);
    }
  }

  @Override
  public ActionMode onWindowStartingActionMode(ActionMode.Callback arg0, int arg1) {
    if (hostActivityDelegate != null) {
      return hostActivityDelegate.onWindowStartingActionMode(arg0, arg1);
    } else {
      return super.onWindowStartingActionMode(arg0, arg1);
    }
  }

  @Override
  public ActionMode onWindowStartingActionMode(ActionMode.Callback arg0) {
    if (hostActivityDelegate != null) {
      return hostActivityDelegate.onWindowStartingActionMode(arg0);
    } else {
      return super.onWindowStartingActionMode(arg0);
    }
  }

  @Override
  public void onPointerCaptureChanged(boolean arg0) {
    if (hostActivityDelegate != null) {
      hostActivityDelegate.onPointerCaptureChanged(arg0);
    } else {
      super.onPointerCaptureChanged(arg0);
    }
  }

  @Override
  protected void onCreate(Bundle arg0) {
    if (hostActivityDelegate != null) {
      hostActivityDelegate.onCreate(arg0);
    } else {
      super.onCreate(arg0);
    }
  }

  @Override
  protected void onDestroy() {
    if (hostActivityDelegate != null) {
      hostActivityDelegate.onDestroy();
    } else {
      super.onDestroy();
    }
  }

  @Override
  protected void onPause() {
    if (hostActivityDelegate != null) {
      hostActivityDelegate.onPause();
    } else {
      super.onPause();
    }
  }

  @Override
  protected void onRestart() {
    if (hostActivityDelegate != null) {
      hostActivityDelegate.onRestart();
    } else {
      super.onRestart();
    }
  }

  @Override
  protected void onResume() {
    if (hostActivityDelegate != null) {
      hostActivityDelegate.onResume();
    } else {
      super.onResume();
    }
  }

  @Override
  protected void onStop() {
    if (hostActivityDelegate != null) {
      hostActivityDelegate.onStop();
    } else {
      super.onStop();
    }
  }

  @Override
  protected void onActivityResult(int arg0, int arg1, Intent arg2) {
    if (hostActivityDelegate != null) {
      hostActivityDelegate.onActivityResult(arg0, arg1, arg2);
    } else {
      super.onActivityResult(arg0, arg1, arg2);
    }
  }

  @Override
  protected void onApplyThemeResource(Resources.Theme arg0, int arg1, boolean arg2) {
    if (hostActivityDelegate != null) {
      hostActivityDelegate.onApplyThemeResource(arg0, arg1, arg2);
    } else {
      super.onApplyThemeResource(arg0, arg1, arg2);
    }
  }

  @Override
  protected void onChildTitleChanged(Activity arg0, CharSequence arg1) {
    if (hostActivityDelegate != null) {
      hostActivityDelegate.onChildTitleChanged(arg0, arg1);
    } else {
      super.onChildTitleChanged(arg0, arg1);
    }
  }

  @Override
  protected Dialog onCreateDialog(int arg0) {
    if (hostActivityDelegate != null) {
      return hostActivityDelegate.onCreateDialog(arg0);
    } else {
      return super.onCreateDialog(arg0);
    }
  }

  @Override
  protected Dialog onCreateDialog(int arg0, Bundle arg1) {
    if (hostActivityDelegate != null) {
      return hostActivityDelegate.onCreateDialog(arg0, arg1);
    } else {
      return super.onCreateDialog(arg0, arg1);
    }
  }

  @Override
  protected void onNewIntent(Intent arg0) {
    if (hostActivityDelegate != null) {
      hostActivityDelegate.onNewIntent(arg0);
    } else {
      super.onNewIntent(arg0);
    }
  }

  @Override
  protected void onPostCreate(Bundle arg0) {
    if (hostActivityDelegate != null) {
      hostActivityDelegate.onPostCreate(arg0);
    } else {
      super.onPostCreate(arg0);
    }
  }

  @Override
  protected void onPostResume() {
    if (hostActivityDelegate != null) {
      hostActivityDelegate.onPostResume();
    } else {
      super.onPostResume();
    }
  }

  @Override
  protected void onPrepareDialog(int arg0, Dialog arg1) {
    if (hostActivityDelegate != null) {
      hostActivityDelegate.onPrepareDialog(arg0, arg1);
    } else {
      super.onPrepareDialog(arg0, arg1);
    }
  }

  @Override
  protected void onPrepareDialog(int arg0, Dialog arg1, Bundle arg2) {
    if (hostActivityDelegate != null) {
      hostActivityDelegate.onPrepareDialog(arg0, arg1, arg2);
    } else {
      super.onPrepareDialog(arg0, arg1, arg2);
    }
  }

  @Override
  protected void onRestoreInstanceState(Bundle arg0) {
    if (hostActivityDelegate != null) {
      hostActivityDelegate.onRestoreInstanceState(arg0);
    } else {
      super.onRestoreInstanceState(arg0);
    }
  }

  @Override
  protected void onSaveInstanceState(Bundle arg0) {
    if (hostActivityDelegate != null) {
      hostActivityDelegate.onSaveInstanceState(arg0);
    } else {
      super.onSaveInstanceState(arg0);
    }
  }

  @Override
  protected void onTitleChanged(CharSequence arg0, int arg1) {
    if (hostActivityDelegate != null) {
      hostActivityDelegate.onTitleChanged(arg0, arg1);
    } else {
      super.onTitleChanged(arg0, arg1);
    }
  }

  @Override
  protected void onUserLeaveHint() {
    if (hostActivityDelegate != null) {
      hostActivityDelegate.onUserLeaveHint();
    } else {
      super.onUserLeaveHint();
    }
  }

  @Override
  protected void onStart() {
    if (hostActivityDelegate != null) {
      hostActivityDelegate.onStart();
    } else {
      super.onStart();
    }
  }

  @Override
  public boolean dispatchPopulateAccessibilityEvent(AccessibilityEvent arg0) {
    if (hostActivityDelegate != null) {
      return hostActivityDelegate.dispatchPopulateAccessibilityEvent(arg0);
    } else {
      return super.dispatchPopulateAccessibilityEvent(arg0);
    }
  }

  @Override
  public boolean dispatchGenericMotionEvent(MotionEvent arg0) {
    if (hostActivityDelegate != null) {
      return hostActivityDelegate.dispatchGenericMotionEvent(arg0);
    } else {
      return super.dispatchGenericMotionEvent(arg0);
    }
  }

  @Override
  public boolean dispatchKeyEvent(KeyEvent arg0) {
    if (hostActivityDelegate != null) {
      return hostActivityDelegate.dispatchKeyEvent(arg0);
    } else {
      return super.dispatchKeyEvent(arg0);
    }
  }

  @Override
  public boolean dispatchKeyShortcutEvent(KeyEvent arg0) {
    if (hostActivityDelegate != null) {
      return hostActivityDelegate.dispatchKeyShortcutEvent(arg0);
    } else {
      return super.dispatchKeyShortcutEvent(arg0);
    }
  }

  @Override
  public boolean dispatchTouchEvent(MotionEvent arg0) {
    if (hostActivityDelegate != null) {
      return hostActivityDelegate.dispatchTouchEvent(arg0);
    } else {
      return super.dispatchTouchEvent(arg0);
    }
  }

  @Override
  public boolean dispatchTrackballEvent(MotionEvent arg0) {
    if (hostActivityDelegate != null) {
      return hostActivityDelegate.dispatchTrackballEvent(arg0);
    } else {
      return super.dispatchTrackballEvent(arg0);
    }
  }

  @Override
  public boolean superIsChangingConfigurations() {
    return super.isChangingConfigurations();
  }

  @Override
  public void superFinish() {
    super.finish();
  }

  @Override
  public ClassLoader superGetClassLoader() {
    return super.getClassLoader();
  }

  @Override
  public LayoutInflater superGetLayoutInflater() {
    return super.getLayoutInflater();
  }

  @Override
  public Resources superGetResources() {
    return super.getResources();
  }

  @Override
  public void superRecreate() {
    super.recreate();
  }

  @Override
  public ComponentName superGetCallingActivity() {
    return super.getCallingActivity();
  }

  @Override
  public void superOnCreateNavigateUpTaskStack(TaskStackBuilder arg0) {
    super.onCreateNavigateUpTaskStack(arg0);
  }

  @Override
  public void superOnLocalVoiceInteractionStarted() {
    super.onLocalVoiceInteractionStarted();
  }

  @Override
  public void superOnLocalVoiceInteractionStopped() {
    super.onLocalVoiceInteractionStopped();
  }

  @Override
  public void superOnPictureInPictureModeChanged(boolean arg0, Configuration arg1) {
    super.onPictureInPictureModeChanged(arg0, arg1);
  }

  @Override
  public void superOnPictureInPictureModeChanged(boolean arg0) {
    super.onPictureInPictureModeChanged(arg0);
  }

  @Override
  public boolean superOnPictureInPictureRequested() {
    return super.onPictureInPictureRequested();
  }

  @Override
  public void superOnPictureInPictureUiStateChanged(PictureInPictureUiState arg0) {
    super.onPictureInPictureUiStateChanged(arg0);
  }

  @Override
  public void superOnPrepareNavigateUpTaskStack(TaskStackBuilder arg0) {
    super.onPrepareNavigateUpTaskStack(arg0);
  }

  @Override
  public Object superOnRetainNonConfigurationInstance() {
    return super.onRetainNonConfigurationInstance();
  }

  @Override
  public void superOnTopResumedActivityChanged(boolean arg0) {
    super.onTopResumedActivityChanged(arg0);
  }

  @Override
  public void superOnCreate(Bundle arg0, PersistableBundle arg1) {
    super.onCreate(arg0, arg1);
  }

  @Override
  public boolean superOnKeyDown(int arg0, KeyEvent arg1) {
    return super.onKeyDown(arg0, arg1);
  }

  @Override
  public boolean superOnKeyUp(int arg0, KeyEvent arg1) {
    return super.onKeyUp(arg0, arg1);
  }

  @Override
  public void superOnActionModeFinished(ActionMode arg0) {
    super.onActionModeFinished(arg0);
  }

  @Override
  public void superOnActionModeStarted(ActionMode arg0) {
    super.onActionModeStarted(arg0);
  }

  @Override
  public void superOnActivityReenter(int arg0, Intent arg1) {
    super.onActivityReenter(arg0, arg1);
  }

  @Override
  public void superOnActivityResult(int arg0, int arg1, Intent arg2, ComponentCaller arg3) {
    super.onActivityResult(arg0, arg1, arg2, arg3);
  }

  @Override
  public void superOnAttachFragment(Fragment arg0) {
    super.onAttachFragment(arg0);
  }

  @Override
  public void superOnAttachedToWindow() {
    super.onAttachedToWindow();
  }

  @Override
  public void superOnBackPressed() {
    super.onBackPressed();
  }

  @Override
  public void superOnConfigurationChanged(Configuration arg0) {
    super.onConfigurationChanged(arg0);
  }

  @Override
  public void superOnContentChanged() {
    super.onContentChanged();
  }

  @Override
  public boolean superOnContextItemSelected(MenuItem arg0) {
    return super.onContextItemSelected(arg0);
  }

  @Override
  public void superOnContextMenuClosed(Menu arg0) {
    super.onContextMenuClosed(arg0);
  }

  @Override
  public void superOnCreateContextMenu(ContextMenu arg0, View arg1,
      ContextMenu.ContextMenuInfo arg2) {
    super.onCreateContextMenu(arg0, arg1, arg2);
  }

  @Override
  public CharSequence superOnCreateDescription() {
    return super.onCreateDescription();
  }

  @Override
  public boolean superOnCreateOptionsMenu(Menu arg0) {
    return super.onCreateOptionsMenu(arg0);
  }

  @Override
  public boolean superOnCreatePanelMenu(int arg0, Menu arg1) {
    return super.onCreatePanelMenu(arg0, arg1);
  }

  @Override
  public View superOnCreatePanelView(int arg0) {
    return super.onCreatePanelView(arg0);
  }

  @Override
  public boolean superOnCreateThumbnail(Bitmap arg0, Canvas arg1) {
    return super.onCreateThumbnail(arg0, arg1);
  }

  @Override
  public View superOnCreateView(View arg0, String arg1, Context arg2, AttributeSet arg3) {
    return super.onCreateView(arg0, arg1, arg2, arg3);
  }

  @Override
  public View superOnCreateView(String arg0, Context arg1, AttributeSet arg2) {
    return super.onCreateView(arg0, arg1, arg2);
  }

  @Override
  public void superOnDetachedFromWindow() {
    super.onDetachedFromWindow();
  }

  @Override
  public void superOnEnterAnimationComplete() {
    super.onEnterAnimationComplete();
  }

  @Override
  public boolean superOnGenericMotionEvent(MotionEvent arg0) {
    return super.onGenericMotionEvent(arg0);
  }

  @Override
  public void superOnGetDirectActions(CancellationSignal arg0, Consumer<List<DirectAction>> arg1) {
    super.onGetDirectActions(arg0, arg1);
  }

  @Override
  public boolean superOnKeyLongPress(int arg0, KeyEvent arg1) {
    return super.onKeyLongPress(arg0, arg1);
  }

  @Override
  public boolean superOnKeyMultiple(int arg0, int arg1, KeyEvent arg2) {
    return super.onKeyMultiple(arg0, arg1, arg2);
  }

  @Override
  public boolean superOnKeyShortcut(int arg0, KeyEvent arg1) {
    return super.onKeyShortcut(arg0, arg1);
  }

  @Override
  public void superOnLowMemory() {
    super.onLowMemory();
  }

  @Override
  public boolean superOnMenuItemSelected(int arg0, MenuItem arg1) {
    return super.onMenuItemSelected(arg0, arg1);
  }

  @Override
  public boolean superOnMenuOpened(int arg0, Menu arg1) {
    return super.onMenuOpened(arg0, arg1);
  }

  @Override
  public void superOnMultiWindowModeChanged(boolean arg0) {
    super.onMultiWindowModeChanged(arg0);
  }

  @Override
  public void superOnMultiWindowModeChanged(boolean arg0, Configuration arg1) {
    super.onMultiWindowModeChanged(arg0, arg1);
  }

  @Override
  public boolean superOnNavigateUp() {
    return super.onNavigateUp();
  }

  @Override
  public boolean superOnNavigateUpFromChild(Activity arg0) {
    return super.onNavigateUpFromChild(arg0);
  }

  @Override
  public void superOnNewIntent(Intent arg0, ComponentCaller arg1) {
    super.onNewIntent(arg0, arg1);
  }

  @Override
  public boolean superOnOptionsItemSelected(MenuItem arg0) {
    return super.onOptionsItemSelected(arg0);
  }

  @Override
  public void superOnOptionsMenuClosed(Menu arg0) {
    super.onOptionsMenuClosed(arg0);
  }

  @Override
  public void superOnPanelClosed(int arg0, Menu arg1) {
    super.onPanelClosed(arg0, arg1);
  }

  @Override
  public void superOnPerformDirectAction(String arg0, Bundle arg1, CancellationSignal arg2,
      Consumer<Bundle> arg3) {
    super.onPerformDirectAction(arg0, arg1, arg2, arg3);
  }

  @Override
  public void superOnPostCreate(Bundle arg0, PersistableBundle arg1) {
    super.onPostCreate(arg0, arg1);
  }

  @Override
  public boolean superOnPrepareOptionsMenu(Menu arg0) {
    return super.onPrepareOptionsMenu(arg0);
  }

  @Override
  public boolean superOnPreparePanel(int arg0, View arg1, Menu arg2) {
    return super.onPreparePanel(arg0, arg1, arg2);
  }

  @Override
  public void superOnProvideAssistContent(AssistContent arg0) {
    super.onProvideAssistContent(arg0);
  }

  @Override
  public void superOnProvideAssistData(Bundle arg0) {
    super.onProvideAssistData(arg0);
  }

  @Override
  public void superOnProvideKeyboardShortcuts(List<KeyboardShortcutGroup> arg0, Menu arg1,
      int arg2) {
    super.onProvideKeyboardShortcuts(arg0, arg1, arg2);
  }

  @Override
  public Uri superOnProvideReferrer() {
    return super.onProvideReferrer();
  }

  @Override
  public void superOnRequestPermissionsResult(int arg0, String[] arg1, int[] arg2) {
    super.onRequestPermissionsResult(arg0, arg1, arg2);
  }

  @Override
  public void superOnRequestPermissionsResult(int arg0, String[] arg1, int[] arg2, int arg3) {
    super.onRequestPermissionsResult(arg0, arg1, arg2, arg3);
  }

  @Override
  public void superOnRestoreInstanceState(Bundle arg0, PersistableBundle arg1) {
    super.onRestoreInstanceState(arg0, arg1);
  }

  @Override
  public void superOnSaveInstanceState(Bundle arg0, PersistableBundle arg1) {
    super.onSaveInstanceState(arg0, arg1);
  }

  @Override
  public boolean superOnSearchRequested(SearchEvent arg0) {
    return super.onSearchRequested(arg0);
  }

  @Override
  public boolean superOnSearchRequested() {
    return super.onSearchRequested();
  }

  @Override
  public void superOnStateNotSaved() {
    super.onStateNotSaved();
  }

  @Override
  public boolean superOnTouchEvent(MotionEvent arg0) {
    return super.onTouchEvent(arg0);
  }

  @Override
  public boolean superOnTrackballEvent(MotionEvent arg0) {
    return super.onTrackballEvent(arg0);
  }

  @Override
  public void superOnTrimMemory(int arg0) {
    super.onTrimMemory(arg0);
  }

  @Override
  public void superOnUserInteraction() {
    super.onUserInteraction();
  }

  @Override
  public void superOnVisibleBehindCanceled() {
    super.onVisibleBehindCanceled();
  }

  @Override
  public void superOnWindowAttributesChanged(WindowManager.LayoutParams arg0) {
    super.onWindowAttributesChanged(arg0);
  }

  @Override
  public void superOnWindowFocusChanged(boolean arg0) {
    super.onWindowFocusChanged(arg0);
  }

  @Override
  public ActionMode superOnWindowStartingActionMode(ActionMode.Callback arg0, int arg1) {
    return super.onWindowStartingActionMode(arg0, arg1);
  }

  @Override
  public ActionMode superOnWindowStartingActionMode(ActionMode.Callback arg0) {
    return super.onWindowStartingActionMode(arg0);
  }

  @Override
  public void superOnPointerCaptureChanged(boolean arg0) {
    super.onPointerCaptureChanged(arg0);
  }

  @Override
  public void superOnCreate(Bundle arg0) {
    super.onCreate(arg0);
  }

  @Override
  public void superOnDestroy() {
    super.onDestroy();
  }

  @Override
  public void superOnPause() {
    super.onPause();
  }

  @Override
  public void superOnRestart() {
    super.onRestart();
  }

  @Override
  public void superOnResume() {
    super.onResume();
  }

  @Override
  public void superOnStop() {
    super.onStop();
  }

  @Override
  public void superOnActivityResult(int arg0, int arg1, Intent arg2) {
    super.onActivityResult(arg0, arg1, arg2);
  }

  @Override
  public void superOnApplyThemeResource(Resources.Theme arg0, int arg1, boolean arg2) {
    super.onApplyThemeResource(arg0, arg1, arg2);
  }

  @Override
  public void superOnChildTitleChanged(Activity arg0, CharSequence arg1) {
    super.onChildTitleChanged(arg0, arg1);
  }

  @Override
  public Dialog superOnCreateDialog(int arg0) {
    return super.onCreateDialog(arg0);
  }

  @Override
  public Dialog superOnCreateDialog(int arg0, Bundle arg1) {
    return super.onCreateDialog(arg0, arg1);
  }

  @Override
  public void superOnNewIntent(Intent arg0) {
    super.onNewIntent(arg0);
  }

  @Override
  public void superOnPostCreate(Bundle arg0) {
    super.onPostCreate(arg0);
  }

  @Override
  public void superOnPostResume() {
    super.onPostResume();
  }

  @Override
  public void superOnPrepareDialog(int arg0, Dialog arg1) {
    super.onPrepareDialog(arg0, arg1);
  }

  @Override
  public void superOnPrepareDialog(int arg0, Dialog arg1, Bundle arg2) {
    super.onPrepareDialog(arg0, arg1, arg2);
  }

  @Override
  public void superOnRestoreInstanceState(Bundle arg0) {
    super.onRestoreInstanceState(arg0);
  }

  @Override
  public void superOnSaveInstanceState(Bundle arg0) {
    super.onSaveInstanceState(arg0);
  }

  @Override
  public void superOnTitleChanged(CharSequence arg0, int arg1) {
    super.onTitleChanged(arg0, arg1);
  }

  @Override
  public void superOnUserLeaveHint() {
    super.onUserLeaveHint();
  }

  @Override
  public void superOnStart() {
    super.onStart();
  }

  @Override
  public boolean superDispatchPopulateAccessibilityEvent(AccessibilityEvent arg0) {
    return super.dispatchPopulateAccessibilityEvent(arg0);
  }

  @Override
  public boolean superDispatchGenericMotionEvent(MotionEvent arg0) {
    return super.dispatchGenericMotionEvent(arg0);
  }

  @Override
  public boolean superDispatchKeyEvent(KeyEvent arg0) {
    return super.dispatchKeyEvent(arg0);
  }

  @Override
  public boolean superDispatchKeyShortcutEvent(KeyEvent arg0) {
    return super.dispatchKeyShortcutEvent(arg0);
  }

  @Override
  public boolean superDispatchTouchEvent(MotionEvent arg0) {
    return super.dispatchTouchEvent(arg0);
  }

  @Override
  public boolean superDispatchTrackballEvent(MotionEvent arg0) {
    return super.dispatchTrackballEvent(arg0);
  }

  @Override
  public void superDump(String arg0, FileDescriptor arg1, PrintWriter arg2, String[] arg3) {
    super.dump(arg0, arg1, arg2, arg3);
  }

  @Override
  public void superClearOverrideActivityTransition(int arg0) {
    super.clearOverrideActivityTransition(arg0);
  }

  @Override
  public void superDismissKeyboardShortcutsHelper() {
    super.dismissKeyboardShortcutsHelper();
  }

  @Override
  public TransitionManager superGetContentTransitionManager() {
    return super.getContentTransitionManager();
  }

  @Override
  public Object superGetLastNonConfigurationInstance() {
    return super.getLastNonConfigurationInstance();
  }

  @Override
  public int superGetMaxNumPictureInPictureActions() {
    return super.getMaxNumPictureInPictureActions();
  }

  @Override
  public boolean superIsActivityTransitionRunning() {
    return super.isActivityTransitionRunning();
  }

  @Override
  public boolean superIsLocalVoiceInteractionSupported() {
    return super.isLocalVoiceInteractionSupported();
  }

  @Override
  public void superRegisterActivityLifecycleCallbacks(Application.ActivityLifecycleCallbacks arg0) {
    super.registerActivityLifecycleCallbacks(arg0);
  }

  @Override
  public void superRegisterScreenCaptureCallback(Executor arg0,
      Activity.ScreenCaptureCallback arg1) {
    super.registerScreenCaptureCallback(arg0, arg1);
  }

  @Override
  public DragAndDropPermissions superRequestDragAndDropPermissions(DragEvent arg0) {
    return super.requestDragAndDropPermissions(arg0);
  }

  @Override
  public void superRequestOpenInBrowserEducation() {
    super.requestOpenInBrowserEducation();
  }

  @Override
  public void superRequestShowKeyboardShortcuts() {
    super.requestShowKeyboardShortcuts();
  }

  @Override
  public void superSetAllowCrossUidActivitySwitchFromBelow(boolean arg0) {
    super.setAllowCrossUidActivitySwitchFromBelow(arg0);
  }

  @Override
  public void superSetContentTransitionManager(TransitionManager arg0) {
    super.setContentTransitionManager(arg0);
  }

  @Override
  public void superSetEnterSharedElementCallback(SharedElementCallback arg0) {
    super.setEnterSharedElementCallback(arg0);
  }

  @Override
  public void superSetExitSharedElementCallback(SharedElementCallback arg0) {
    super.setExitSharedElementCallback(arg0);
  }

  @Override
  public void superSetProgressBarIndeterminate(boolean arg0) {
    super.setProgressBarIndeterminate(arg0);
  }

  @Override
  public void superSetProgressBarIndeterminateVisibility(boolean arg0) {
    super.setProgressBarIndeterminateVisibility(arg0);
  }

  @Override
  public void superSetRecentsScreenshotEnabled(boolean arg0) {
    super.setRecentsScreenshotEnabled(arg0);
  }

  @Override
  public boolean superShouldShowRequestPermissionRationale(String arg0) {
    return super.shouldShowRequestPermissionRationale(arg0);
  }

  @Override
  public boolean superShouldShowRequestPermissionRationale(String arg0, int arg1) {
    return super.shouldShowRequestPermissionRationale(arg0, arg1);
  }

  @Override
  public void superStartPostponedEnterTransition() {
    super.startPostponedEnterTransition();
  }

  @Override
  public void superUnregisterActivityLifecycleCallbacks(
      Application.ActivityLifecycleCallbacks arg0) {
    super.unregisterActivityLifecycleCallbacks(arg0);
  }

  @Override
  public void superUnregisterComponentCallbacks(ComponentCallbacks arg0) {
    super.unregisterComponentCallbacks(arg0);
  }

  @Override
  public void superUnregisterScreenCaptureCallback(Activity.ScreenCaptureCallback arg0) {
    super.unregisterScreenCaptureCallback(arg0);
  }

  @Override
  public ComponentCaller superGetCaller() {
    return super.getCaller();
  }

  @Override
  public Intent superGetIntent() {
    return super.getIntent();
  }

  @Override
  public int superGetTaskId() {
    return super.getTaskId();
  }

  @Override
  public CharSequence superGetTitle() {
    return super.getTitle();
  }

  @Override
  public Window superGetWindow() {
    return super.getWindow();
  }

  @Override
  public boolean superIsChild() {
    return super.isChild();
  }

  @Override
  public boolean superIsTaskRoot() {
    return super.isTaskRoot();
  }

  @Override
  public void superSetIntent(Intent arg0) {
    super.setIntent(arg0);
  }

  @Override
  public void superSetIntent(Intent arg0, ComponentCaller arg1) {
    super.setIntent(arg0, arg1);
  }

  @Override
  public void superSetTheme(int arg0) {
    super.setTheme(arg0);
  }

  @Override
  public void superSetTitle(int arg0) {
    super.setTitle(arg0);
  }

  @Override
  public void superSetTitle(CharSequence arg0) {
    super.setTitle(arg0);
  }

  @Override
  public void superSetVisible(boolean arg0) {
    super.setVisible(arg0);
  }

  @Override
  public boolean superShowAssist(Bundle arg0) {
    return super.showAssist(arg0);
  }

  @Override
  public boolean superShowDialog(int arg0, Bundle arg1) {
    return super.showDialog(arg0, arg1);
  }

  @Override
  public void superShowDialog(int arg0) {
    super.showDialog(arg0);
  }

  @Override
  public void superAddContentView(View arg0, ViewGroup.LayoutParams arg1) {
    super.addContentView(arg0, arg1);
  }

  @Override
  public void superCloseContextMenu() {
    super.closeContextMenu();
  }

  @Override
  public void superCloseOptionsMenu() {
    super.closeOptionsMenu();
  }

  @Override
  public PendingIntent superCreatePendingResult(int arg0, Intent arg1, int arg2) {
    return super.createPendingResult(arg0, arg1, arg2);
  }

  @Override
  public void superDismissDialog(int arg0) {
    super.dismissDialog(arg0);
  }

  @Override
  public void superEnterPictureInPictureMode() {
    super.enterPictureInPictureMode();
  }

  @Override
  public boolean superEnterPictureInPictureMode(PictureInPictureParams arg0) {
    return super.enterPictureInPictureMode(arg0);
  }

  @Override
  public <T extends View> T superFindViewById(int arg0) {
    return super.findViewById(arg0);
  }

  @Override
  public void superFinishActivity(int arg0) {
    super.finishActivity(arg0);
  }

  @Override
  public void superFinishActivityFromChild(Activity arg0, int arg1) {
    super.finishActivityFromChild(arg0, arg1);
  }

  @Override
  public void superFinishAffinity() {
    super.finishAffinity();
  }

  @Override
  public void superFinishAfterTransition() {
    super.finishAfterTransition();
  }

  @Override
  public void superFinishAndRemoveTask() {
    super.finishAndRemoveTask();
  }

  @Override
  public void superFinishFromChild(Activity arg0) {
    super.finishFromChild(arg0);
  }

  @Override
  public ActionBar superGetActionBar() {
    return super.getActionBar();
  }

  @Override
  public Application superGetApplication() {
    return super.getApplication();
  }

  @Override
  public String superGetCallingPackage() {
    return super.getCallingPackage();
  }

  @Override
  public int superGetChangingConfigurations() {
    return super.getChangingConfigurations();
  }

  @Override
  public ComponentName superGetComponentName() {
    return super.getComponentName();
  }

  @Override
  public Scene superGetContentScene() {
    return super.getContentScene();
  }

  @Override
  public ComponentCaller superGetCurrentCaller() {
    return super.getCurrentCaller();
  }

  @Override
  public View superGetCurrentFocus() {
    return super.getCurrentFocus();
  }

  @Override
  public FragmentManager superGetFragmentManager() {
    return super.getFragmentManager();
  }

  @Override
  public ComponentCaller superGetInitialCaller() {
    return super.getInitialCaller();
  }

  @Override
  public String superGetLaunchedFromPackage() {
    return super.getLaunchedFromPackage();
  }

  @Override
  public int superGetLaunchedFromUid() {
    return super.getLaunchedFromUid();
  }

  @Override
  public LoaderManager superGetLoaderManager() {
    return super.getLoaderManager();
  }

  @Override
  public String superGetLocalClassName() {
    return super.getLocalClassName();
  }

  @Override
  public MediaController superGetMediaController() {
    return super.getMediaController();
  }

  @Override
  public MenuInflater superGetMenuInflater() {
    return super.getMenuInflater();
  }

  @Override
  public OnBackInvokedDispatcher superGetOnBackInvokedDispatcher() {
    return super.getOnBackInvokedDispatcher();
  }

  @Override
  public Intent superGetParentActivityIntent() {
    return super.getParentActivityIntent();
  }

  @Override
  public SharedPreferences superGetPreferences(int arg0) {
    return super.getPreferences(arg0);
  }

  @Override
  public Uri superGetReferrer() {
    return super.getReferrer();
  }

  @Override
  public int superGetRequestedOrientation() {
    return super.getRequestedOrientation();
  }

  @Override
  public SearchEvent superGetSearchEvent() {
    return super.getSearchEvent();
  }

  @Override
  public SplashScreen superGetSplashScreen() {
    return super.getSplashScreen();
  }

  @Override
  public Object superGetSystemService(String arg0) {
    return super.getSystemService(arg0);
  }

  @Override
  public int superGetTitleColor() {
    return super.getTitleColor();
  }

  @Override
  public VoiceInteractor superGetVoiceInteractor() {
    return super.getVoiceInteractor();
  }

  @Override
  public int superGetVolumeControlStream() {
    return super.getVolumeControlStream();
  }

  @Override
  public WindowManager superGetWindowManager() {
    return super.getWindowManager();
  }

  @Override
  public boolean superHasWindowFocus() {
    return super.hasWindowFocus();
  }

  @Override
  public void superInvalidateOptionsMenu() {
    super.invalidateOptionsMenu();
  }

  @Override
  public boolean superIsFinishing() {
    return super.isFinishing();
  }

  @Override
  public boolean superIsImmersive() {
    return super.isImmersive();
  }

  @Override
  public boolean superIsInMultiWindowMode() {
    return super.isInMultiWindowMode();
  }

  @Override
  public boolean superIsInPictureInPictureMode() {
    return super.isInPictureInPictureMode();
  }

  @Override
  public boolean superIsLaunchedFromBubble() {
    return super.isLaunchedFromBubble();
  }

  @Override
  public boolean superIsVoiceInteraction() {
    return super.isVoiceInteraction();
  }

  @Override
  public boolean superIsVoiceInteractionRoot() {
    return super.isVoiceInteractionRoot();
  }

  @Override
  public Cursor superManagedQuery(Uri arg0, String[] arg1, String arg2, String[] arg3,
      String arg4) {
    return super.managedQuery(arg0, arg1, arg2, arg3, arg4);
  }

  @Override
  public boolean superMoveTaskToBack(boolean arg0) {
    return super.moveTaskToBack(arg0);
  }

  @Override
  public boolean superNavigateUpTo(Intent arg0) {
    return super.navigateUpTo(arg0);
  }

  @Override
  public boolean superNavigateUpToFromChild(Activity arg0, Intent arg1) {
    return super.navigateUpToFromChild(arg0, arg1);
  }

  @Override
  public void superOpenContextMenu(View arg0) {
    super.openContextMenu(arg0);
  }

  @Override
  public void superOpenOptionsMenu() {
    super.openOptionsMenu();
  }

  @Override
  public void superOverrideActivityTransition(int arg0, int arg1, int arg2, int arg3) {
    super.overrideActivityTransition(arg0, arg1, arg2, arg3);
  }

  @Override
  public void superOverrideActivityTransition(int arg0, int arg1, int arg2) {
    super.overrideActivityTransition(arg0, arg1, arg2);
  }

  @Override
  public void superOverridePendingTransition(int arg0, int arg1, int arg2) {
    super.overridePendingTransition(arg0, arg1, arg2);
  }

  @Override
  public void superOverridePendingTransition(int arg0, int arg1) {
    super.overridePendingTransition(arg0, arg1);
  }

  @Override
  public void superPostponeEnterTransition() {
    super.postponeEnterTransition();
  }

  @Override
  public void superRegisterComponentCallbacks(ComponentCallbacks arg0) {
    super.registerComponentCallbacks(arg0);
  }

  @Override
  public void superRegisterForContextMenu(View arg0) {
    super.registerForContextMenu(arg0);
  }

  @Override
  public boolean superReleaseInstance() {
    return super.releaseInstance();
  }

  @Override
  public void superRemoveDialog(int arg0) {
    super.removeDialog(arg0);
  }

  @Override
  public void superReportFullyDrawn() {
    super.reportFullyDrawn();
  }

  @Override
  public void superRequestFullscreenMode(int arg0, OutcomeReceiver<Void, Throwable> arg1) {
    super.requestFullscreenMode(arg0, arg1);
  }

  @Override
  public void superRequestPermissions(String[] arg0, int arg1) {
    super.requestPermissions(arg0, arg1);
  }

  @Override
  public void superRequestPermissions(String[] arg0, int arg1, int arg2) {
    super.requestPermissions(arg0, arg1, arg2);
  }

  @Override
  public boolean superRequestVisibleBehind(boolean arg0) {
    return super.requestVisibleBehind(arg0);
  }

  @Override
  public boolean superRequestWindowFeature(int arg0) {
    return super.requestWindowFeature(arg0);
  }

  @Override
  public <T extends View> T superRequireViewById(int arg0) {
    return super.requireViewById(arg0);
  }

  @Override
  public void superRunOnUiThread(Runnable arg0) {
    super.runOnUiThread(arg0);
  }

  @Override
  public void superSetActionBar(Toolbar arg0) {
    super.setActionBar(arg0);
  }

  @Override
  public void superSetContentView(int arg0) {
    super.setContentView(arg0);
  }

  @Override
  public void superSetContentView(View arg0, ViewGroup.LayoutParams arg1) {
    super.setContentView(arg0, arg1);
  }

  @Override
  public void superSetContentView(View arg0) {
    super.setContentView(arg0);
  }

  @Override
  public void superSetDefaultKeyMode(int arg0) {
    super.setDefaultKeyMode(arg0);
  }

  @Override
  public void superSetFeatureDrawable(int arg0, Drawable arg1) {
    super.setFeatureDrawable(arg0, arg1);
  }

  @Override
  public void superSetFeatureDrawableAlpha(int arg0, int arg1) {
    super.setFeatureDrawableAlpha(arg0, arg1);
  }

  @Override
  public void superSetFeatureDrawableResource(int arg0, int arg1) {
    super.setFeatureDrawableResource(arg0, arg1);
  }

  @Override
  public void superSetFeatureDrawableUri(int arg0, Uri arg1) {
    super.setFeatureDrawableUri(arg0, arg1);
  }

  @Override
  public void superSetFinishOnTouchOutside(boolean arg0) {
    super.setFinishOnTouchOutside(arg0);
  }

  @Override
  public void superSetImmersive(boolean arg0) {
    super.setImmersive(arg0);
  }

  @Override
  public void superSetInheritShowWhenLocked(boolean arg0) {
    super.setInheritShowWhenLocked(arg0);
  }

  @Override
  public void superSetLocusContext(LocusId arg0, Bundle arg1) {
    super.setLocusContext(arg0, arg1);
  }

  @Override
  public void superSetMediaController(MediaController arg0) {
    super.setMediaController(arg0);
  }

  @Override
  public void superSetPictureInPictureParams(PictureInPictureParams arg0) {
    super.setPictureInPictureParams(arg0);
  }

  @Override
  public void superSetProgress(int arg0) {
    super.setProgress(arg0);
  }

  @Override
  public void superSetProgressBarVisibility(boolean arg0) {
    super.setProgressBarVisibility(arg0);
  }

  @Override
  public void superSetRequestedOrientation(int arg0) {
    super.setRequestedOrientation(arg0);
  }

  @Override
  public void superSetSecondaryProgress(int arg0) {
    super.setSecondaryProgress(arg0);
  }

  @Override
  public void superSetShouldDockBigOverlays(boolean arg0) {
    super.setShouldDockBigOverlays(arg0);
  }

  @Override
  public boolean superShouldDockBigOverlays() {
    return super.shouldDockBigOverlays();
  }

  @Override
  public void superSetShowWhenLocked(boolean arg0) {
    super.setShowWhenLocked(arg0);
  }

  @Override
  public void superSetTaskDescription(ActivityManager.TaskDescription arg0) {
    super.setTaskDescription(arg0);
  }

  @Override
  public void superSetTitleColor(int arg0) {
    super.setTitleColor(arg0);
  }

  @Override
  public boolean superSetTranslucent(boolean arg0) {
    return super.setTranslucent(arg0);
  }

  @Override
  public void superSetTurnScreenOn(boolean arg0) {
    super.setTurnScreenOn(arg0);
  }

  @Override
  public void superSetVolumeControlStream(int arg0) {
    super.setVolumeControlStream(arg0);
  }

  @Override
  public void superSetVrModeEnabled(boolean arg0, ComponentName arg1) throws
      PackageManager.NameNotFoundException {
    super.setVrModeEnabled(arg0, arg1);
  }

  @Override
  public boolean superShouldUpRecreateTask(Intent arg0) {
    return super.shouldUpRecreateTask(arg0);
  }

  @Override
  public void superShowLockTaskEscapeMessage() {
    super.showLockTaskEscapeMessage();
  }

  @Override
  public ActionMode superStartActionMode(ActionMode.Callback arg0) {
    return super.startActionMode(arg0);
  }

  @Override
  public ActionMode superStartActionMode(ActionMode.Callback arg0, int arg1) {
    return super.startActionMode(arg0, arg1);
  }

  @Override
  public void superStartActivities(Intent[] arg0, Bundle arg1) {
    super.startActivities(arg0, arg1);
  }

  @Override
  public void superStartActivities(Intent[] arg0) {
    super.startActivities(arg0);
  }

  @Override
  public void superStartActivity(Intent arg0, Bundle arg1) {
    super.startActivity(arg0, arg1);
  }

  @Override
  public void superStartActivity(Intent arg0) {
    super.startActivity(arg0);
  }

  @Override
  public void superStartActivityForResult(Intent arg0, int arg1) {
    super.startActivityForResult(arg0, arg1);
  }

  @Override
  public void superStartActivityForResult(Intent arg0, int arg1, Bundle arg2) {
    super.startActivityForResult(arg0, arg1, arg2);
  }

  @Override
  public void superStartActivityFromChild(Activity arg0, Intent arg1, int arg2, Bundle arg3) {
    super.startActivityFromChild(arg0, arg1, arg2, arg3);
  }

  @Override
  public void superStartActivityFromChild(Activity arg0, Intent arg1, int arg2) {
    super.startActivityFromChild(arg0, arg1, arg2);
  }

  @Override
  public void superStartActivityFromFragment(Fragment arg0, Intent arg1, int arg2, Bundle arg3) {
    super.startActivityFromFragment(arg0, arg1, arg2, arg3);
  }

  @Override
  public void superStartActivityFromFragment(Fragment arg0, Intent arg1, int arg2) {
    super.startActivityFromFragment(arg0, arg1, arg2);
  }

  @Override
  public boolean superStartActivityIfNeeded(Intent arg0, int arg1) {
    return super.startActivityIfNeeded(arg0, arg1);
  }

  @Override
  public boolean superStartActivityIfNeeded(Intent arg0, int arg1, Bundle arg2) {
    return super.startActivityIfNeeded(arg0, arg1, arg2);
  }

  @Override
  public void superStartIntentSender(IntentSender arg0, Intent arg1, int arg2, int arg3, int arg4)
      throws IntentSender.SendIntentException {
    super.startIntentSender(arg0, arg1, arg2, arg3, arg4);
  }

  @Override
  public void superStartIntentSender(IntentSender arg0, Intent arg1, int arg2, int arg3, int arg4,
      Bundle arg5) throws IntentSender.SendIntentException {
    super.startIntentSender(arg0, arg1, arg2, arg3, arg4, arg5);
  }

  @Override
  public void superStartIntentSenderForResult(IntentSender arg0, int arg1, Intent arg2, int arg3,
      int arg4, int arg5, Bundle arg6) throws IntentSender.SendIntentException {
    super.startIntentSenderForResult(arg0, arg1, arg2, arg3, arg4, arg5, arg6);
  }

  @Override
  public void superStartIntentSenderForResult(IntentSender arg0, int arg1, Intent arg2, int arg3,
      int arg4, int arg5) throws IntentSender.SendIntentException {
    super.startIntentSenderForResult(arg0, arg1, arg2, arg3, arg4, arg5);
  }

  @Override
  public void superStartIntentSenderFromChild(Activity arg0, IntentSender arg1, int arg2,
      Intent arg3, int arg4, int arg5, int arg6, Bundle arg7) throws
      IntentSender.SendIntentException {
    super.startIntentSenderFromChild(arg0, arg1, arg2, arg3, arg4, arg5, arg6, arg7);
  }

  @Override
  public void superStartIntentSenderFromChild(Activity arg0, IntentSender arg1, int arg2,
      Intent arg3, int arg4, int arg5, int arg6) throws IntentSender.SendIntentException {
    super.startIntentSenderFromChild(arg0, arg1, arg2, arg3, arg4, arg5, arg6);
  }

  @Override
  public void superStartLocalVoiceInteraction(Bundle arg0) {
    super.startLocalVoiceInteraction(arg0);
  }

  @Override
  public void superStartLockTask() {
    super.startLockTask();
  }

  @Override
  public void superStartManagingCursor(Cursor arg0) {
    super.startManagingCursor(arg0);
  }

  @Override
  public boolean superStartNextMatchingActivity(Intent arg0) {
    return super.startNextMatchingActivity(arg0);
  }

  @Override
  public boolean superStartNextMatchingActivity(Intent arg0, Bundle arg1) {
    return super.startNextMatchingActivity(arg0, arg1);
  }

  @Override
  public void superStartSearch(String arg0, boolean arg1, Bundle arg2, boolean arg3) {
    super.startSearch(arg0, arg1, arg2, arg3);
  }

  @Override
  public void superStopLocalVoiceInteraction() {
    super.stopLocalVoiceInteraction();
  }

  @Override
  public void superStopLockTask() {
    super.stopLockTask();
  }

  @Override
  public void superStopManagingCursor(Cursor arg0) {
    super.stopManagingCursor(arg0);
  }

  @Override
  public void superTakeKeyEvents(boolean arg0) {
    super.takeKeyEvents(arg0);
  }

  @Override
  public void superTriggerSearch(String arg0, Bundle arg1) {
    super.triggerSearch(arg0, arg1);
  }

  @Override
  public void superUnregisterForContextMenu(View arg0) {
    super.unregisterForContextMenu(arg0);
  }

  @Override
  public Activity superGetParent() {
    return super.getParent();
  }

  @Override
  public boolean superIsDestroyed() {
    return super.isDestroyed();
  }

  @Override
  public void superSetResult(int arg0, Intent arg1) {
    super.setResult(arg0, arg1);
  }

  @Override
  public void superSetResult(int arg0) {
    super.setResult(arg0);
  }

  @Override
  public void superSetTheme(Resources.Theme arg0) {
    super.setTheme(arg0);
  }

  @Override
  public AssetManager superGetAssets() {
    return super.getAssets();
  }

  @Override
  public Resources.Theme superGetTheme() {
    return super.getTheme();
  }

  @Override
  public void superApplyOverrideConfiguration(Configuration arg0) {
    super.applyOverrideConfiguration(arg0);
  }

  @Override
  public int superCheckCallingOrSelfPermission(String arg0) {
    return super.checkCallingOrSelfPermission(arg0);
  }

  @Override
  public int superCheckCallingOrSelfUriPermission(Uri arg0, int arg1) {
    return super.checkCallingOrSelfUriPermission(arg0, arg1);
  }

  @Override
  public int[] superCheckCallingOrSelfUriPermissions(List<Uri> arg0, int arg1) {
    return super.checkCallingOrSelfUriPermissions(arg0, arg1);
  }

  @Override
  public int superCheckContentUriPermissionFull(Uri arg0, int arg1, int arg2, int arg3) {
    return super.checkContentUriPermissionFull(arg0, arg1, arg2, arg3);
  }

  @Override
  public Context superCreateDeviceProtectedStorageContext() {
    return super.createDeviceProtectedStorageContext();
  }

  @Override
  public void superEnforceCallingOrSelfPermission(String arg0, String arg1) {
    super.enforceCallingOrSelfPermission(arg0, arg1);
  }

  @Override
  public void superEnforceCallingOrSelfUriPermission(Uri arg0, int arg1, String arg2) {
    super.enforceCallingOrSelfUriPermission(arg0, arg1, arg2);
  }

  @Override
  public void superEnforceCallingUriPermission(Uri arg0, int arg1, String arg2) {
    super.enforceCallingUriPermission(arg0, arg1, arg2);
  }

  @Override
  public int superGetWallpaperDesiredMinimumHeight() {
    return super.getWallpaperDesiredMinimumHeight();
  }

  @Override
  public int superGetWallpaperDesiredMinimumWidth() {
    return super.getWallpaperDesiredMinimumWidth();
  }

  @Override
  public void superRegisterDeviceIdChangeListener(Executor arg0, IntConsumer arg1) {
    super.registerDeviceIdChangeListener(arg0, arg1);
  }

  @Override
  public void superRemoveStickyBroadcastAsUser(Intent arg0, UserHandle arg1) {
    super.removeStickyBroadcastAsUser(arg0, arg1);
  }

  @Override
  public void superRevokeSelfPermissionsOnKill(Collection<String> arg0) {
    super.revokeSelfPermissionsOnKill(arg0);
  }

  @Override
  public void superSendStickyOrderedBroadcastAsUser(Intent arg0, UserHandle arg1,
      BroadcastReceiver arg2, Handler arg3, int arg4, String arg5, Bundle arg6) {
    super.sendStickyOrderedBroadcastAsUser(arg0, arg1, arg2, arg3, arg4, arg5, arg6);
  }

  @Override
  public void superUnregisterDeviceIdChangeListener(IntConsumer arg0) {
    super.unregisterDeviceIdChangeListener(arg0);
  }

  @Override
  public boolean superDeleteFile(String arg0) {
    return super.deleteFile(arg0);
  }

  @Override
  public String[] superFileList() {
    return super.fileList();
  }

  @Override
  public File superGetDataDir() {
    return super.getDataDir();
  }

  @Override
  public File superGetDir(String arg0, int arg1) {
    return super.getDir(arg0, arg1);
  }

  @Override
  public Display superGetDisplay() {
    return super.getDisplay();
  }

  @Override
  public File superGetObbDir() {
    return super.getObbDir();
  }

  @Override
  public File[] superGetObbDirs() {
    return super.getObbDirs();
  }

  @Override
  public ContextParams superGetParams() {
    return super.getParams();
  }

  @Override
  public boolean superBindIsolatedService(Intent arg0, int arg1, String arg2, Executor arg3,
      ServiceConnection arg4) {
    return super.bindIsolatedService(arg0, arg1, arg2, arg3, arg4);
  }

  @Override
  public boolean superBindService(Intent arg0, int arg1, Executor arg2, ServiceConnection arg3) {
    return super.bindService(arg0, arg1, arg2, arg3);
  }

  @Override
  public boolean superBindService(Intent arg0, ServiceConnection arg1,
      Context.BindServiceFlags arg2) {
    return super.bindService(arg0, arg1, arg2);
  }

  @Override
  public boolean superBindService(Intent arg0, Context.BindServiceFlags arg1, Executor arg2,
      ServiceConnection arg3) {
    return super.bindService(arg0, arg1, arg2, arg3);
  }

  @Override
  public boolean superBindService(Intent arg0, ServiceConnection arg1, int arg2) {
    return super.bindService(arg0, arg1, arg2);
  }

  @Override
  public boolean superBindServiceAsUser(Intent arg0, ServiceConnection arg1, int arg2,
      UserHandle arg3) {
    return super.bindServiceAsUser(arg0, arg1, arg2, arg3);
  }

  @Override
  public boolean superBindServiceAsUser(Intent arg0, ServiceConnection arg1,
      Context.BindServiceFlags arg2, UserHandle arg3) {
    return super.bindServiceAsUser(arg0, arg1, arg2, arg3);
  }

  @Override
  public int superCheckCallingPermission(String arg0) {
    return super.checkCallingPermission(arg0);
  }

  @Override
  public int superCheckCallingUriPermission(Uri arg0, int arg1) {
    return super.checkCallingUriPermission(arg0, arg1);
  }

  @Override
  public int[] superCheckCallingUriPermissions(List<Uri> arg0, int arg1) {
    return super.checkCallingUriPermissions(arg0, arg1);
  }

  @Override
  public int superCheckSelfPermission(String arg0) {
    return super.checkSelfPermission(arg0);
  }

  @Override
  public int superCheckUriPermission(Uri arg0, int arg1, int arg2, int arg3) {
    return super.checkUriPermission(arg0, arg1, arg2, arg3);
  }

  @Override
  public int superCheckUriPermission(Uri arg0, String arg1, String arg2, int arg3, int arg4,
      int arg5) {
    return super.checkUriPermission(arg0, arg1, arg2, arg3, arg4, arg5);
  }

  @Override
  public int[] superCheckUriPermissions(List<Uri> arg0, int arg1, int arg2, int arg3) {
    return super.checkUriPermissions(arg0, arg1, arg2, arg3);
  }

  @Override
  public void superClearWallpaper() throws IOException {
    super.clearWallpaper();
  }

  @Override
  public Context superCreateAttributionContext(String arg0) {
    return super.createAttributionContext(arg0);
  }

  @Override
  public Context superCreateConfigurationContext(Configuration arg0) {
    return super.createConfigurationContext(arg0);
  }

  @Override
  public Context superCreateContext(ContextParams arg0) {
    return super.createContext(arg0);
  }

  @Override
  public Context superCreateContextForSplit(String arg0) throws
      PackageManager.NameNotFoundException {
    return super.createContextForSplit(arg0);
  }

  @Override
  public Context superCreateDeviceContext(int arg0) {
    return super.createDeviceContext(arg0);
  }

  @Override
  public Context superCreateDisplayContext(Display arg0) {
    return super.createDisplayContext(arg0);
  }

  @Override
  public Context superCreatePackageContext(String arg0, int arg1) throws
      PackageManager.NameNotFoundException {
    return super.createPackageContext(arg0, arg1);
  }

  @Override
  public Context superCreateWindowContext(Display arg0, int arg1, Bundle arg2) {
    return super.createWindowContext(arg0, arg1, arg2);
  }

  @Override
  public Context superCreateWindowContext(int arg0, Bundle arg1) {
    return super.createWindowContext(arg0, arg1);
  }

  @Override
  public String[] superDatabaseList() {
    return super.databaseList();
  }

  @Override
  public boolean superDeleteDatabase(String arg0) {
    return super.deleteDatabase(arg0);
  }

  @Override
  public boolean superDeleteSharedPreferences(String arg0) {
    return super.deleteSharedPreferences(arg0);
  }

  @Override
  public void superEnforceCallingPermission(String arg0, String arg1) {
    super.enforceCallingPermission(arg0, arg1);
  }

  @Override
  public void superEnforcePermission(String arg0, int arg1, int arg2, String arg3) {
    super.enforcePermission(arg0, arg1, arg2, arg3);
  }

  @Override
  public void superEnforceUriPermission(Uri arg0, String arg1, String arg2, int arg3, int arg4,
      int arg5, String arg6) {
    super.enforceUriPermission(arg0, arg1, arg2, arg3, arg4, arg5, arg6);
  }

  @Override
  public void superEnforceUriPermission(Uri arg0, int arg1, int arg2, int arg3, String arg4) {
    super.enforceUriPermission(arg0, arg1, arg2, arg3, arg4);
  }

  @Override
  public Context superGetApplicationContext() {
    return super.getApplicationContext();
  }

  @Override
  public ApplicationInfo superGetApplicationInfo() {
    return super.getApplicationInfo();
  }

  @Override
  public AttributionSource superGetAttributionSource() {
    return super.getAttributionSource();
  }

  @Override
  public String superGetAttributionTag() {
    return super.getAttributionTag();
  }

  @Override
  public Context superGetBaseContext() {
    return super.getBaseContext();
  }

  @Override
  public File superGetCacheDir() {
    return super.getCacheDir();
  }

  @Override
  public File superGetCodeCacheDir() {
    return super.getCodeCacheDir();
  }

  @Override
  public ContentResolver superGetContentResolver() {
    return super.getContentResolver();
  }

  @Override
  public File superGetDatabasePath(String arg0) {
    return super.getDatabasePath(arg0);
  }

  @Override
  public int superGetDeviceId() {
    return super.getDeviceId();
  }

  @Override
  public File superGetExternalCacheDir() {
    return super.getExternalCacheDir();
  }

  @Override
  public File[] superGetExternalCacheDirs() {
    return super.getExternalCacheDirs();
  }

  @Override
  public File superGetExternalFilesDir(String arg0) {
    return super.getExternalFilesDir(arg0);
  }

  @Override
  public File[] superGetExternalFilesDirs(String arg0) {
    return super.getExternalFilesDirs(arg0);
  }

  @Override
  public File[] superGetExternalMediaDirs() {
    return super.getExternalMediaDirs();
  }

  @Override
  public File superGetFileStreamPath(String arg0) {
    return super.getFileStreamPath(arg0);
  }

  @Override
  public File superGetFilesDir() {
    return super.getFilesDir();
  }

  @Override
  public Executor superGetMainExecutor() {
    return super.getMainExecutor();
  }

  @Override
  public Looper superGetMainLooper() {
    return super.getMainLooper();
  }

  @Override
  public File superGetNoBackupFilesDir() {
    return super.getNoBackupFilesDir();
  }

  @Override
  public String superGetOpPackageName() {
    return super.getOpPackageName();
  }

  @Override
  public String superGetPackageCodePath() {
    return super.getPackageCodePath();
  }

  @Override
  public PackageManager superGetPackageManager() {
    return super.getPackageManager();
  }

  @Override
  public String superGetPackageResourcePath() {
    return super.getPackageResourcePath();
  }

  @Override
  public SharedPreferences superGetSharedPreferences(String arg0, int arg1) {
    return super.getSharedPreferences(arg0, arg1);
  }

  @Override
  public String superGetSystemServiceName(Class<?> arg0) {
    return super.getSystemServiceName(arg0);
  }

  @Override
  public Drawable superGetWallpaper() {
    return super.getWallpaper();
  }

  @Override
  public void superGrantUriPermission(String arg0, Uri arg1, int arg2) {
    super.grantUriPermission(arg0, arg1, arg2);
  }

  @Override
  public boolean superIsDeviceProtectedStorage() {
    return super.isDeviceProtectedStorage();
  }

  @Override
  public boolean superIsRestricted() {
    return super.isRestricted();
  }

  @Override
  public boolean superIsUiContext() {
    return super.isUiContext();
  }

  @Override
  public boolean superMoveDatabaseFrom(Context arg0, String arg1) {
    return super.moveDatabaseFrom(arg0, arg1);
  }

  @Override
  public boolean superMoveSharedPreferencesFrom(Context arg0, String arg1) {
    return super.moveSharedPreferencesFrom(arg0, arg1);
  }

  @Override
  public FileInputStream superOpenFileInput(String arg0) throws FileNotFoundException {
    return super.openFileInput(arg0);
  }

  @Override
  public FileOutputStream superOpenFileOutput(String arg0, int arg1) throws FileNotFoundException {
    return super.openFileOutput(arg0, arg1);
  }

  @Override
  public SQLiteDatabase superOpenOrCreateDatabase(String arg0, int arg1,
      SQLiteDatabase.CursorFactory arg2) {
    return super.openOrCreateDatabase(arg0, arg1, arg2);
  }

  @Override
  public SQLiteDatabase superOpenOrCreateDatabase(String arg0, int arg1,
      SQLiteDatabase.CursorFactory arg2, DatabaseErrorHandler arg3) {
    return super.openOrCreateDatabase(arg0, arg1, arg2, arg3);
  }

  @Override
  public Drawable superPeekWallpaper() {
    return super.peekWallpaper();
  }

  @Override
  public Intent superRegisterReceiver(BroadcastReceiver arg0, IntentFilter arg1, String arg2,
      Handler arg3, int arg4) {
    return super.registerReceiver(arg0, arg1, arg2, arg3, arg4);
  }

  @Override
  public Intent superRegisterReceiver(BroadcastReceiver arg0, IntentFilter arg1, int arg2) {
    return super.registerReceiver(arg0, arg1, arg2);
  }

  @Override
  public Intent superRegisterReceiver(BroadcastReceiver arg0, IntentFilter arg1, String arg2,
      Handler arg3) {
    return super.registerReceiver(arg0, arg1, arg2, arg3);
  }

  @Override
  public Intent superRegisterReceiver(BroadcastReceiver arg0, IntentFilter arg1) {
    return super.registerReceiver(arg0, arg1);
  }

  @Override
  public void superRemoveStickyBroadcast(Intent arg0) {
    super.removeStickyBroadcast(arg0);
  }

  @Override
  public void superRevokeUriPermission(String arg0, Uri arg1, int arg2) {
    super.revokeUriPermission(arg0, arg1, arg2);
  }

  @Override
  public void superRevokeUriPermission(Uri arg0, int arg1) {
    super.revokeUriPermission(arg0, arg1);
  }

  @Override
  public void superSendBroadcast(Intent arg0) {
    super.sendBroadcast(arg0);
  }

  @Override
  public void superSendBroadcast(Intent arg0, String arg1) {
    super.sendBroadcast(arg0, arg1);
  }

  @Override
  public void superSendBroadcast(Intent arg0, String arg1, Bundle arg2) {
    super.sendBroadcast(arg0, arg1, arg2);
  }

  @Override
  public void superSendBroadcastAsUser(Intent arg0, UserHandle arg1) {
    super.sendBroadcastAsUser(arg0, arg1);
  }

  @Override
  public void superSendBroadcastAsUser(Intent arg0, UserHandle arg1, String arg2) {
    super.sendBroadcastAsUser(arg0, arg1, arg2);
  }

  @Override
  public void superSendOrderedBroadcast(Intent arg0, String arg1, BroadcastReceiver arg2,
      Handler arg3, int arg4, String arg5, Bundle arg6) {
    super.sendOrderedBroadcast(arg0, arg1, arg2, arg3, arg4, arg5, arg6);
  }

  @Override
  public void superSendOrderedBroadcast(Intent arg0, String arg1) {
    super.sendOrderedBroadcast(arg0, arg1);
  }

  @Override
  public void superSendOrderedBroadcast(Intent arg0, int arg1, String arg2, String arg3,
      BroadcastReceiver arg4, Handler arg5, String arg6, Bundle arg7, Bundle arg8) {
    super.sendOrderedBroadcast(arg0, arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8);
  }

  @Override
  public void superSendOrderedBroadcast(Intent arg0, String arg1, Bundle arg2,
      BroadcastReceiver arg3, Handler arg4, int arg5, String arg6, Bundle arg7) {
    super.sendOrderedBroadcast(arg0, arg1, arg2, arg3, arg4, arg5, arg6, arg7);
  }

  @Override
  public void superSendOrderedBroadcast(Intent arg0, String arg1, Bundle arg2) {
    super.sendOrderedBroadcast(arg0, arg1, arg2);
  }

  @Override
  public void superSendOrderedBroadcast(Intent arg0, String arg1, String arg2,
      BroadcastReceiver arg3, Handler arg4, int arg5, String arg6, Bundle arg7) {
    super.sendOrderedBroadcast(arg0, arg1, arg2, arg3, arg4, arg5, arg6, arg7);
  }

  @Override
  public void superSendOrderedBroadcastAsUser(Intent arg0, UserHandle arg1, String arg2,
      BroadcastReceiver arg3, Handler arg4, int arg5, String arg6, Bundle arg7) {
    super.sendOrderedBroadcastAsUser(arg0, arg1, arg2, arg3, arg4, arg5, arg6, arg7);
  }

  @Override
  public void superSendStickyBroadcast(Intent arg0, Bundle arg1) {
    super.sendStickyBroadcast(arg0, arg1);
  }

  @Override
  public void superSendStickyBroadcast(Intent arg0) {
    super.sendStickyBroadcast(arg0);
  }

  @Override
  public void superSendStickyBroadcastAsUser(Intent arg0, UserHandle arg1) {
    super.sendStickyBroadcastAsUser(arg0, arg1);
  }

  @Override
  public void superSendStickyOrderedBroadcast(Intent arg0, BroadcastReceiver arg1, Handler arg2,
      int arg3, String arg4, Bundle arg5) {
    super.sendStickyOrderedBroadcast(arg0, arg1, arg2, arg3, arg4, arg5);
  }

  @Override
  public void superSetWallpaper(Bitmap arg0) throws IOException {
    super.setWallpaper(arg0);
  }

  @Override
  public void superSetWallpaper(InputStream arg0) throws IOException {
    super.setWallpaper(arg0);
  }

  @Override
  public ComponentName superStartForegroundService(Intent arg0) {
    return super.startForegroundService(arg0);
  }

  @Override
  public boolean superStartInstrumentation(ComponentName arg0, String arg1, Bundle arg2) {
    return super.startInstrumentation(arg0, arg1, arg2);
  }

  @Override
  public ComponentName superStartService(Intent arg0) {
    return super.startService(arg0);
  }

  @Override
  public boolean superStopService(Intent arg0) {
    return super.stopService(arg0);
  }

  @Override
  public void superUnbindService(ServiceConnection arg0) {
    super.unbindService(arg0);
  }

  @Override
  public void superUnregisterReceiver(BroadcastReceiver arg0) {
    super.unregisterReceiver(arg0);
  }

  @Override
  public void superUpdateServiceGroup(ServiceConnection arg0, int arg1, int arg2) {
    super.updateServiceGroup(arg0, arg1, arg2);
  }

  @Override
  public int superCheckPermission(String arg0, int arg1, int arg2) {
    return super.checkPermission(arg0, arg1, arg2);
  }

  @Override
  public String superGetPackageName() {
    return super.getPackageName();
  }

  @Override
  public void superSendBroadcastWithMultiplePermissions(Intent arg0, String[] arg1) {
    super.sendBroadcastWithMultiplePermissions(arg0, arg1);
  }

  @Override
  public boolean superBindIsolatedService(Intent arg0, Context.BindServiceFlags arg1, String arg2,
      Executor arg3, ServiceConnection arg4) {
    return super.bindIsolatedService(arg0, arg1, arg2, arg3, arg4);
  }

  @Override
  public void superRevokeSelfPermissionOnKill(String arg0) {
    super.revokeSelfPermissionOnKill(arg0);
  }

  @Override
  public void superAttachBaseContext(Context arg0) {
    super.attachBaseContext(arg0);
  }

  public void attachBaseContext(Context arg0) {
    super.attachBaseContext(arg0);
  }
}
