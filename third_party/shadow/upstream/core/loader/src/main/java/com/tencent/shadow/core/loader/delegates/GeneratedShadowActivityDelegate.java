package com.tencent.shadow.core.loader.delegates;

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.app.Fragment;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.net.Uri;
import android.os.Bundle;
import android.util.AttributeSet;
import android.view.ActionMode;
import android.view.ContextMenu;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import com.tencent.shadow.core.runtime.GeneratedPluginActivity;
import com.tencent.shadow.core.runtime.container.GeneratedHostActivityDelegate;
import java.lang.CharSequence;
import java.lang.ClassLoader;
import java.lang.Object;
import java.lang.Override;
import java.lang.String;
import java.lang.SuppressWarnings;

/**
 * 由
 * {@link com.tencent.shadow.coding.code_generator.ActivityCodeGenerator}
 * 自动生成
 */
@SuppressLint("NewApi")
@SuppressWarnings({"unchecked", "JavadocReference"})
public abstract class GeneratedShadowActivityDelegate extends ShadowDelegate implements GeneratedHostActivityDelegate {
  GeneratedPluginActivity pluginActivity;

  @Override
  public boolean isChangingConfigurations() {
    return pluginActivity.isChangingConfigurations();
  }

  @Override
  public void finish() {
    pluginActivity.finish();
  }

  @Override
  public ClassLoader getClassLoader() {
    return pluginActivity.getClassLoader();
  }

  @Override
  public LayoutInflater getLayoutInflater() {
    return pluginActivity.getLayoutInflater();
  }

  @Override
  public Resources getResources() {
    return pluginActivity.getResources();
  }

  @Override
  public void recreate() {
    pluginActivity.recreate();
  }

  @Override
  public ComponentName getCallingActivity() {
    return pluginActivity.getCallingActivity();
  }

  @Override
  public void onCreateNavigateUpTaskStack(Object arg0) {
    pluginActivity.onCreateNavigateUpTaskStack((android.app.TaskStackBuilder) arg0);
  }

  @Override
  public void onLocalVoiceInteractionStarted() {
    pluginActivity.onLocalVoiceInteractionStarted();
  }

  @Override
  public void onLocalVoiceInteractionStopped() {
    pluginActivity.onLocalVoiceInteractionStopped();
  }

  @Override
  public void onPictureInPictureModeChanged(boolean arg0, Configuration arg1) {
    pluginActivity.onPictureInPictureModeChanged(arg0, arg1);
  }

  @Override
  public void onPictureInPictureModeChanged(boolean arg0) {
    pluginActivity.onPictureInPictureModeChanged(arg0);
  }

  @Override
  public boolean onPictureInPictureRequested() {
    return pluginActivity.onPictureInPictureRequested();
  }

  @Override
  public void onPictureInPictureUiStateChanged(Object arg0) {
    pluginActivity.onPictureInPictureUiStateChanged((android.app.PictureInPictureUiState) arg0);
  }

  @Override
  public void onPrepareNavigateUpTaskStack(Object arg0) {
    pluginActivity.onPrepareNavigateUpTaskStack((android.app.TaskStackBuilder) arg0);
  }

  @Override
  public Object onRetainNonConfigurationInstance() {
    return pluginActivity.onRetainNonConfigurationInstance();
  }

  @Override
  public void onTopResumedActivityChanged(boolean arg0) {
    pluginActivity.onTopResumedActivityChanged(arg0);
  }

  @Override
  public void onCreate(Bundle arg0, Object arg1) {
    pluginActivity.onCreate(arg0, (android.os.PersistableBundle) arg1);
  }

  @Override
  public boolean onKeyDown(int arg0, KeyEvent arg1) {
    return pluginActivity.onKeyDown(arg0, arg1);
  }

  @Override
  public boolean onKeyUp(int arg0, KeyEvent arg1) {
    return pluginActivity.onKeyUp(arg0, arg1);
  }

  @Override
  public void onActionModeFinished(ActionMode arg0) {
    pluginActivity.onActionModeFinished(arg0);
  }

  @Override
  public void onActionModeStarted(ActionMode arg0) {
    pluginActivity.onActionModeStarted(arg0);
  }

  @Override
  public void onActivityReenter(int arg0, Intent arg1) {
    pluginActivity.onActivityReenter(arg0, arg1);
  }

  @Override
  public void onActivityResult(int arg0, int arg1, Intent arg2, Object arg3) {
    pluginActivity.onActivityResult(arg0, arg1, arg2, (android.app.ComponentCaller) arg3);
  }

  @Override
  public void onAttachFragment(Fragment arg0) {
    pluginActivity.onAttachFragment(arg0);
  }

  @Override
  public void onAttachedToWindow() {
    pluginActivity.onAttachedToWindow();
  }

  @Override
  public void onBackPressed() {
    pluginActivity.onBackPressed();
  }

  @Override
  public void onConfigurationChanged(Configuration arg0) {
    pluginActivity.onConfigurationChanged(arg0);
  }

  @Override
  public void onContentChanged() {
    pluginActivity.onContentChanged();
  }

  @Override
  public boolean onContextItemSelected(MenuItem arg0) {
    return pluginActivity.onContextItemSelected(arg0);
  }

  @Override
  public void onContextMenuClosed(Menu arg0) {
    pluginActivity.onContextMenuClosed(arg0);
  }

  @Override
  public void onCreateContextMenu(ContextMenu arg0, View arg1, ContextMenu.ContextMenuInfo arg2) {
    pluginActivity.onCreateContextMenu(arg0, arg1, arg2);
  }

  @Override
  public CharSequence onCreateDescription() {
    return pluginActivity.onCreateDescription();
  }

  @Override
  public boolean onCreateOptionsMenu(Menu arg0) {
    return pluginActivity.onCreateOptionsMenu(arg0);
  }

  @Override
  public boolean onCreatePanelMenu(int arg0, Menu arg1) {
    return pluginActivity.onCreatePanelMenu(arg0, arg1);
  }

  @Override
  public View onCreatePanelView(int arg0) {
    return pluginActivity.onCreatePanelView(arg0);
  }

  @Override
  public boolean onCreateThumbnail(Bitmap arg0, Canvas arg1) {
    return pluginActivity.onCreateThumbnail(arg0, arg1);
  }

  @Override
  public View onCreateView(View arg0, String arg1, Context arg2, AttributeSet arg3) {
    return pluginActivity.onCreateView(arg0, arg1, arg2, arg3);
  }

  @Override
  public View onCreateView(String arg0, Context arg1, AttributeSet arg2) {
    return pluginActivity.onCreateView(arg0, arg1, arg2);
  }

  @Override
  public void onDetachedFromWindow() {
    pluginActivity.onDetachedFromWindow();
  }

  @Override
  public void onEnterAnimationComplete() {
    pluginActivity.onEnterAnimationComplete();
  }

  @Override
  public boolean onGenericMotionEvent(MotionEvent arg0) {
    return pluginActivity.onGenericMotionEvent(arg0);
  }

  @Override
  public void onGetDirectActions(Object arg0, Object arg1) {
    pluginActivity.onGetDirectActions((android.os.CancellationSignal) arg0, (java.util.function.Consumer<java.util.List<android.app.DirectAction>>) arg1);
  }

  @Override
  public boolean onKeyLongPress(int arg0, KeyEvent arg1) {
    return pluginActivity.onKeyLongPress(arg0, arg1);
  }

  @Override
  public boolean onKeyMultiple(int arg0, int arg1, KeyEvent arg2) {
    return pluginActivity.onKeyMultiple(arg0, arg1, arg2);
  }

  @Override
  public boolean onKeyShortcut(int arg0, KeyEvent arg1) {
    return pluginActivity.onKeyShortcut(arg0, arg1);
  }

  @Override
  public void onLowMemory() {
    pluginActivity.onLowMemory();
  }

  @Override
  public boolean onMenuItemSelected(int arg0, MenuItem arg1) {
    return pluginActivity.onMenuItemSelected(arg0, arg1);
  }

  @Override
  public boolean onMenuOpened(int arg0, Menu arg1) {
    return pluginActivity.onMenuOpened(arg0, arg1);
  }

  @Override
  public void onMultiWindowModeChanged(boolean arg0) {
    pluginActivity.onMultiWindowModeChanged(arg0);
  }

  @Override
  public void onMultiWindowModeChanged(boolean arg0, Configuration arg1) {
    pluginActivity.onMultiWindowModeChanged(arg0, arg1);
  }

  @Override
  public boolean onNavigateUp() {
    return pluginActivity.onNavigateUp();
  }

  @Override
  public void onNewIntent(Intent arg0, Object arg1) {
    pluginActivity.onNewIntent(arg0, (android.app.ComponentCaller) arg1);
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem arg0) {
    return pluginActivity.onOptionsItemSelected(arg0);
  }

  @Override
  public void onOptionsMenuClosed(Menu arg0) {
    pluginActivity.onOptionsMenuClosed(arg0);
  }

  @Override
  public void onPanelClosed(int arg0, Menu arg1) {
    pluginActivity.onPanelClosed(arg0, arg1);
  }

  @Override
  public void onPerformDirectAction(String arg0, Bundle arg1, Object arg2, Object arg3) {
    pluginActivity.onPerformDirectAction(arg0, arg1, (android.os.CancellationSignal) arg2, (java.util.function.Consumer<android.os.Bundle>) arg3);
  }

  @Override
  public void onPostCreate(Bundle arg0, Object arg1) {
    pluginActivity.onPostCreate(arg0, (android.os.PersistableBundle) arg1);
  }

  @Override
  public boolean onPrepareOptionsMenu(Menu arg0) {
    return pluginActivity.onPrepareOptionsMenu(arg0);
  }

  @Override
  public boolean onPreparePanel(int arg0, View arg1, Menu arg2) {
    return pluginActivity.onPreparePanel(arg0, arg1, arg2);
  }

  @Override
  public void onProvideAssistContent(Object arg0) {
    pluginActivity.onProvideAssistContent((android.app.assist.AssistContent) arg0);
  }

  @Override
  public void onProvideAssistData(Bundle arg0) {
    pluginActivity.onProvideAssistData(arg0);
  }

  @Override
  public void onProvideKeyboardShortcuts(Object arg0, Menu arg1, int arg2) {
    pluginActivity.onProvideKeyboardShortcuts((java.util.List<android.view.KeyboardShortcutGroup>) arg0, arg1, arg2);
  }

  @Override
  public Uri onProvideReferrer() {
    return pluginActivity.onProvideReferrer();
  }

  @Override
  public void onRequestPermissionsResult(int arg0, String[] arg1, int[] arg2) {
    pluginActivity.onRequestPermissionsResult(arg0, arg1, arg2);
  }

  @Override
  public void onRequestPermissionsResult(int arg0, String[] arg1, int[] arg2, int arg3) {
    pluginActivity.onRequestPermissionsResult(arg0, arg1, arg2, arg3);
  }

  @Override
  public void onRestoreInstanceState(Bundle arg0, Object arg1) {
    pluginActivity.onRestoreInstanceState(arg0, (android.os.PersistableBundle) arg1);
  }

  @Override
  public void onSaveInstanceState(Bundle arg0, Object arg1) {
    pluginActivity.onSaveInstanceState(arg0, (android.os.PersistableBundle) arg1);
  }

  @Override
  public boolean onSearchRequested(Object arg0) {
    return pluginActivity.onSearchRequested((android.view.SearchEvent) arg0);
  }

  @Override
  public boolean onSearchRequested() {
    return pluginActivity.onSearchRequested();
  }

  @Override
  public void onStateNotSaved() {
    pluginActivity.onStateNotSaved();
  }

  @Override
  public boolean onTouchEvent(MotionEvent arg0) {
    return pluginActivity.onTouchEvent(arg0);
  }

  @Override
  public boolean onTrackballEvent(MotionEvent arg0) {
    return pluginActivity.onTrackballEvent(arg0);
  }

  @Override
  public void onTrimMemory(int arg0) {
    pluginActivity.onTrimMemory(arg0);
  }

  @Override
  public void onUserInteraction() {
    pluginActivity.onUserInteraction();
  }

  @Override
  public void onVisibleBehindCanceled() {
    pluginActivity.onVisibleBehindCanceled();
  }

  @Override
  public void onWindowAttributesChanged(WindowManager.LayoutParams arg0) {
    pluginActivity.onWindowAttributesChanged(arg0);
  }

  @Override
  public void onWindowFocusChanged(boolean arg0) {
    pluginActivity.onWindowFocusChanged(arg0);
  }

  @Override
  public ActionMode onWindowStartingActionMode(ActionMode.Callback arg0, int arg1) {
    return pluginActivity.onWindowStartingActionMode(arg0, arg1);
  }

  @Override
  public ActionMode onWindowStartingActionMode(ActionMode.Callback arg0) {
    return pluginActivity.onWindowStartingActionMode(arg0);
  }

  @Override
  public void onPointerCaptureChanged(boolean arg0) {
    pluginActivity.onPointerCaptureChanged(arg0);
  }

  @Override
  public void onCreate(Bundle arg0) {
    pluginActivity.onCreate(arg0);
  }

  @Override
  public void onDestroy() {
    pluginActivity.onDestroy();
  }

  @Override
  public void onPause() {
    pluginActivity.onPause();
  }

  @Override
  public void onRestart() {
    pluginActivity.onRestart();
  }

  @Override
  public void onResume() {
    pluginActivity.onResume();
  }

  @Override
  public void onStop() {
    pluginActivity.onStop();
  }

  @Override
  public void onActivityResult(int arg0, int arg1, Intent arg2) {
    pluginActivity.onActivityResult(arg0, arg1, arg2);
  }

  @Override
  public void onApplyThemeResource(Resources.Theme arg0, int arg1, boolean arg2) {
    pluginActivity.onApplyThemeResource(arg0, arg1, arg2);
  }

  @Override
  public Dialog onCreateDialog(int arg0) {
    return pluginActivity.onCreateDialog(arg0);
  }

  @Override
  public Dialog onCreateDialog(int arg0, Bundle arg1) {
    return pluginActivity.onCreateDialog(arg0, arg1);
  }

  @Override
  public void onNewIntent(Intent arg0) {
    pluginActivity.onNewIntent(arg0);
  }

  @Override
  public void onPostCreate(Bundle arg0) {
    pluginActivity.onPostCreate(arg0);
  }

  @Override
  public void onPostResume() {
    pluginActivity.onPostResume();
  }

  @Override
  public void onPrepareDialog(int arg0, Dialog arg1) {
    pluginActivity.onPrepareDialog(arg0, arg1);
  }

  @Override
  public void onPrepareDialog(int arg0, Dialog arg1, Bundle arg2) {
    pluginActivity.onPrepareDialog(arg0, arg1, arg2);
  }

  @Override
  public void onRestoreInstanceState(Bundle arg0) {
    pluginActivity.onRestoreInstanceState(arg0);
  }

  @Override
  public void onSaveInstanceState(Bundle arg0) {
    pluginActivity.onSaveInstanceState(arg0);
  }

  @Override
  public void onTitleChanged(CharSequence arg0, int arg1) {
    pluginActivity.onTitleChanged(arg0, arg1);
  }

  @Override
  public void onUserLeaveHint() {
    pluginActivity.onUserLeaveHint();
  }

  @Override
  public void onStart() {
    pluginActivity.onStart();
  }

  @Override
  public boolean dispatchPopulateAccessibilityEvent(AccessibilityEvent arg0) {
    return pluginActivity.dispatchPopulateAccessibilityEvent(arg0);
  }

  @Override
  public boolean dispatchGenericMotionEvent(MotionEvent arg0) {
    return pluginActivity.dispatchGenericMotionEvent(arg0);
  }

  @Override
  public boolean dispatchKeyEvent(KeyEvent arg0) {
    return pluginActivity.dispatchKeyEvent(arg0);
  }

  @Override
  public boolean dispatchKeyShortcutEvent(KeyEvent arg0) {
    return pluginActivity.dispatchKeyShortcutEvent(arg0);
  }

  @Override
  public boolean dispatchTouchEvent(MotionEvent arg0) {
    return pluginActivity.dispatchTouchEvent(arg0);
  }

  @Override
  public boolean dispatchTrackballEvent(MotionEvent arg0) {
    return pluginActivity.dispatchTrackballEvent(arg0);
  }
}
