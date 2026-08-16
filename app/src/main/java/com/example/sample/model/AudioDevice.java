package com.example.sample.model;

import android.media.AudioDeviceInfo;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;

public class AudioDevice implements Parcelable {
    public final int id;
    public final String name;
    public final int type;
    public final boolean input;
    public final boolean output;

    public AudioDevice(int id, String name, int type, boolean input, boolean output) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.input = input;
        this.output = output;
    }

    protected AudioDevice(Parcel in) {
        id = in.readInt();
        name = in.readString();
        type = in.readInt();
        input = in.readByte() != 0;
        output = in.readByte() != 0;
    }

    public static final Creator<AudioDevice> CREATOR = new Creator<AudioDevice>() {
        @Override
        public AudioDevice createFromParcel(Parcel in) {
            return new AudioDevice(in);
        }

        @Override
        public AudioDevice[] newArray(int size) {
            return new AudioDevice[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(id);
        dest.writeString(name);
        dest.writeInt(type);
        dest.writeByte((byte) (input ? 1 : 0));
        dest.writeByte((byte) (output ? 1 : 0));
    }

    @Override
    public String toString() {
        return name;
    }

    public static String nameForType(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER:
                return "Speaker";
            case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE:
                return "Earpiece";
            case AudioDeviceInfo.TYPE_BUILTIN_MIC:
                return "Phone Mic";
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
                return "Bluetooth";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
                return "Wired Headset";
            case AudioDeviceInfo.TYPE_USB_HEADSET:
            case AudioDeviceInfo.TYPE_USB_DEVICE:
                return "USB Audio";
            case AudioDeviceInfo.TYPE_LINE_ANALOG:
            case AudioDeviceInfo.TYPE_LINE_DIGITAL:
                return "Line";
            case AudioDeviceInfo.TYPE_TELEPHONY:
                return "Phone";
            default:
                return "Device " + type;
        }
    }

    public static String buildName(AudioDeviceInfo device, boolean input) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && device.getProductName() != null) {
            String product = device.getProductName().toString().trim();
            if (!product.isEmpty()) {
                return product;
            }
        }
        return nameForType(device.getType());
    }
}
