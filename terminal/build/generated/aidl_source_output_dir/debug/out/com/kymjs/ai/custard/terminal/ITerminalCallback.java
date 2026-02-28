/*
 * This file is auto-generated.  DO NOT MODIFY.
 * Using: /Users/kymjs/Library/Android/sdk/build-tools/35.0.0/aidl -p/Users/kymjs/Library/Android/sdk/platforms/android-34/framework.aidl -o/Users/kymjs/code/0oslab/Custard/terminal/build/generated/aidl_source_output_dir/debug/out -I/Users/kymjs/code/0oslab/Custard/terminal/src/main/aidl -I/Users/kymjs/code/0oslab/Custard/terminal/src/debug/aidl -I/Users/kymjs/.gradle/caches/8.13/transforms/55513a25b4220f0b6dd4a1f534ccbf98/transformed/core-1.12.0/aidl -I/Users/kymjs/.gradle/caches/8.13/transforms/138a39c91ac9ffab6b1554405c30b5ac/transformed/versionedparcelable-1.1.1/aidl -d/var/folders/z6/sjfl76792tbdjcr62mh803280000gn/T/aidl10126397018458281028.d /Users/kymjs/code/0oslab/Custard/terminal/src/main/aidl/com/kymjs/ai/custard/terminal/ITerminalCallback.aidl
 */
package com.kymjs.ai.custard.terminal;
public interface ITerminalCallback extends android.os.IInterface
{
  /** Default implementation for ITerminalCallback. */
  public static class Default implements com.kymjs.ai.custard.terminal.ITerminalCallback
  {
    @Override public void onCommandExecutionUpdate(com.kymjs.ai.custard.terminal.CommandExecutionEvent event) throws android.os.RemoteException
    {
    }
    @Override public void onSessionDirectoryChanged(com.kymjs.ai.custard.terminal.SessionDirectoryEvent event) throws android.os.RemoteException
    {
    }
    @Override
    public android.os.IBinder asBinder() {
      return null;
    }
  }
  /** Local-side IPC implementation stub class. */
  public static abstract class Stub extends android.os.Binder implements com.kymjs.ai.custard.terminal.ITerminalCallback
  {
    /** Construct the stub at attach it to the interface. */
    @SuppressWarnings("this-escape")
    public Stub()
    {
      this.attachInterface(this, DESCRIPTOR);
    }
    /**
     * Cast an IBinder object into an com.kymjs.ai.custard.terminal.ITerminalCallback interface,
     * generating a proxy if needed.
     */
    public static com.kymjs.ai.custard.terminal.ITerminalCallback asInterface(android.os.IBinder obj)
    {
      if ((obj==null)) {
        return null;
      }
      android.os.IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
      if (((iin!=null)&&(iin instanceof com.kymjs.ai.custard.terminal.ITerminalCallback))) {
        return ((com.kymjs.ai.custard.terminal.ITerminalCallback)iin);
      }
      return new com.kymjs.ai.custard.terminal.ITerminalCallback.Stub.Proxy(obj);
    }
    @Override public android.os.IBinder asBinder()
    {
      return this;
    }
    @Override public boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply, int flags) throws android.os.RemoteException
    {
      java.lang.String descriptor = DESCRIPTOR;
      if (code >= android.os.IBinder.FIRST_CALL_TRANSACTION && code <= android.os.IBinder.LAST_CALL_TRANSACTION) {
        data.enforceInterface(descriptor);
      }
      if (code == INTERFACE_TRANSACTION) {
        reply.writeString(descriptor);
        return true;
      }
      switch (code)
      {
        case TRANSACTION_onCommandExecutionUpdate:
        {
          com.kymjs.ai.custard.terminal.CommandExecutionEvent _arg0;
          _arg0 = _Parcel.readTypedObject(data, com.kymjs.ai.custard.terminal.CommandExecutionEvent.CREATOR);
          this.onCommandExecutionUpdate(_arg0);
          break;
        }
        case TRANSACTION_onSessionDirectoryChanged:
        {
          com.kymjs.ai.custard.terminal.SessionDirectoryEvent _arg0;
          _arg0 = _Parcel.readTypedObject(data, com.kymjs.ai.custard.terminal.SessionDirectoryEvent.CREATOR);
          this.onSessionDirectoryChanged(_arg0);
          break;
        }
        default:
        {
          return super.onTransact(code, data, reply, flags);
        }
      }
      return true;
    }
    private static class Proxy implements com.kymjs.ai.custard.terminal.ITerminalCallback
    {
      private android.os.IBinder mRemote;
      Proxy(android.os.IBinder remote)
      {
        mRemote = remote;
      }
      @Override public android.os.IBinder asBinder()
      {
        return mRemote;
      }
      public java.lang.String getInterfaceDescriptor()
      {
        return DESCRIPTOR;
      }
      @Override public void onCommandExecutionUpdate(com.kymjs.ai.custard.terminal.CommandExecutionEvent event) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _Parcel.writeTypedObject(_data, event, 0);
          boolean _status = mRemote.transact(Stub.TRANSACTION_onCommandExecutionUpdate, _data, null, android.os.IBinder.FLAG_ONEWAY);
        }
        finally {
          _data.recycle();
        }
      }
      @Override public void onSessionDirectoryChanged(com.kymjs.ai.custard.terminal.SessionDirectoryEvent event) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _Parcel.writeTypedObject(_data, event, 0);
          boolean _status = mRemote.transact(Stub.TRANSACTION_onSessionDirectoryChanged, _data, null, android.os.IBinder.FLAG_ONEWAY);
        }
        finally {
          _data.recycle();
        }
      }
    }
    static final int TRANSACTION_onCommandExecutionUpdate = (android.os.IBinder.FIRST_CALL_TRANSACTION + 0);
    static final int TRANSACTION_onSessionDirectoryChanged = (android.os.IBinder.FIRST_CALL_TRANSACTION + 1);
  }
  /** @hide */
  public static final java.lang.String DESCRIPTOR = "com.kymjs.ai.custard.terminal.ITerminalCallback";
  public void onCommandExecutionUpdate(com.kymjs.ai.custard.terminal.CommandExecutionEvent event) throws android.os.RemoteException;
  public void onSessionDirectoryChanged(com.kymjs.ai.custard.terminal.SessionDirectoryEvent event) throws android.os.RemoteException;
  /** @hide */
  static class _Parcel {
    static private <T> T readTypedObject(
        android.os.Parcel parcel,
        android.os.Parcelable.Creator<T> c) {
      if (parcel.readInt() != 0) {
          return c.createFromParcel(parcel);
      } else {
          return null;
      }
    }
    static private <T extends android.os.Parcelable> void writeTypedObject(
        android.os.Parcel parcel, T value, int parcelableFlags) {
      if (value != null) {
        parcel.writeInt(1);
        value.writeToParcel(parcel, parcelableFlags);
      } else {
        parcel.writeInt(0);
      }
    }
  }
}
