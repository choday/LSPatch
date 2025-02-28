package org.lsposed.lspatch.service;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;
import android.widget.Toast;

import org.lsposed.lspatch.share.Constants;
import org.lsposed.lspd.models.Module;
import org.lsposed.lspd.service.ILSPApplicationService;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class RemoteApplicationService implements ILSPApplicationService {

    private static final String TAG = "LSPatch";
    private static final String MODULE_SERVICE = Constants.MANAGER_PACKAGE_NAME + ".manager.ModuleService";

    private ILSPApplicationService service;

    @SuppressLint("DiscouragedPrivateApi")
    public RemoteApplicationService(Context context) throws RemoteException {
        try {
            var intent = new Intent()
                    .setComponent(new ComponentName(Constants.MANAGER_PACKAGE_NAME, MODULE_SERVICE))
                    .putExtra("packageName", context.getPackageName());
            // TODO: Authentication
            var latch = new CountDownLatch(1);
            var conn = new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder binder) {
                    Log.i(TAG, "Manager binder received");
                    latch.countDown();
                    service = Stub.asInterface(binder);
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    Log.e(TAG, "Manager service died");
                    service = null;
                }
            };
            Log.i(TAG, "Request manager binder");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.bindService(intent, Context.BIND_AUTO_CREATE, Executors.newSingleThreadExecutor(), conn);
            } else {
                var handlerThread = new HandlerThread("RemoteApplicationService");
                handlerThread.start();
                var handler = new Handler(handlerThread.getLooper());
                var contextImplClass = context.getClass();
                var getUserMethod = contextImplClass.getMethod("getUser");
                var bindServiceAsUserMethod = contextImplClass.getDeclaredMethod(
                        "bindServiceAsUser", Intent.class, ServiceConnection.class, int.class, Handler.class, UserHandle.class);
                var userHandle = (UserHandle) getUserMethod.invoke(context);
                bindServiceAsUserMethod.invoke(context, intent, conn, Context.BIND_AUTO_CREATE, handler, userHandle);
            }
            boolean success = latch.await(10, TimeUnit.SECONDS);
            if (!success) throw new TimeoutException("Bind service timeout");
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException | InterruptedException | TimeoutException e) {
            Log.e(TAG,e.toString());
            //Toast.makeText(context, "Manager died", Toast.LENGTH_SHORT).show();
            //when latch.await timeout,Toast will Caused by: java.lang.NullPointerException: Attempt to invoke virtual method 'android.content.Context android.app.Application.getApplicationContext()' on a null object reference
            var r = new RemoteException("Failed to get manager binder");
            r.initCause(e);
            throw r;
        }
    }

    @Override
    public IBinder requestModuleBinder(String name) {
        return service == null ? null : service.asBinder();
    }

    @Override
    public List<Module> getModulesList() throws RemoteException {
        return service == null ? new ArrayList<>() : service.getModulesList();
    }

    @Override
    public String getPrefsPath(String packageName) {
        return new File(Environment.getDataDirectory(), "data/" + packageName + "/shared_prefs/").getAbsolutePath();
    }

    @Override
    public Bundle requestRemotePreference(String packageName, int userId, IBinder callback) throws RemoteException {
        return service == null ? null : service.requestRemotePreference(packageName, userId, callback);
    }

    @Override
    public IBinder asBinder() {
        return service == null ? null : service.asBinder();
    }

    @Override
    public ParcelFileDescriptor requestInjectedManagerBinder(List<IBinder> binder) {
        return null;
    }
}
