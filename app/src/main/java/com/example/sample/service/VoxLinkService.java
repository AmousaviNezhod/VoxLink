package com.example.sample.service;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;

public class VoxLinkService extends Service {
    private final IBinder binder = new LocalBinder();

    public class LocalBinder extends Binder {
        public VoxLinkService getService() {
            return VoxLinkService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }
}
