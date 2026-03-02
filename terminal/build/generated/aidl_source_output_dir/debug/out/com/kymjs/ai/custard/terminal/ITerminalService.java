/*
 * This file is auto-generated.  DO NOT MODIFY.
 * Using: /Users/kymjs/Library/Android/sdk/build-tools/35.0.0/aidl -p/Users/kymjs/Library/Android/sdk/platforms/android-34/framework.aidl -o/Users/kymjs/Documents/Custard/terminal/build/generated/aidl_source_output_dir/debug/out -I/Users/kymjs/Documents/Custard/terminal/src/main/aidl -I/Users/kymjs/Documents/Custard/terminal/src/debug/aidl -I/Users/kymjs/.gradle/caches/8.13/transforms/55513a25b4220f0b6dd4a1f534ccbf98/transformed/core-1.12.0/aidl -I/Users/kymjs/.gradle/caches/8.13/transforms/138a39c91ac9ffab6b1554405c30b5ac/transformed/versionedparcelable-1.1.1/aidl -d/var/folders/0w/yw34pdp10v33pdpl10rcdk7h0000gn/T/aidl3731666009430997719.d /Users/kymjs/Documents/Custard/terminal/src/main/aidl/com/kymjs/ai/custard/terminal/ITerminalService.aidl
 */
package com.kymjs.ai.custard.terminal;
public interface ITerminalService extends android.os.IInterface
{
  /** Default implementation for ITerminalService. */
  public static class Default implements com.kymjs.ai.custard.terminal.ITerminalService
  {
    @Override public java.lang.String createSession() throws android.os.RemoteException
    {
      return null;
    }
    @Override public void switchToSession(java.lang.String sessionId) throws android.os.RemoteException
    {
    }
    @Override public void closeSession(java.lang.String sessionId) throws android.os.RemoteException
    {
    }
    @Override public java.lang.String sendCommand(java.lang.String command) throws android.os.RemoteException
    {
      return null;
    }
    @Override public void sendInterruptSignal() throws android.os.RemoteException
    {
    }
    @Override public void registerCallback(com.kymjs.ai.custard.terminal.ITerminalCallback callback) throws android.os.RemoteException
    {
    }
    @Override public void unregisterCallback(com.kymjs.ai.custard.terminal.ITerminalCallback callback) throws android.os.RemoteException
    {
    }
    @Override public void requestStateUpdate() throws android.os.RemoteException
    {
    }
    @Override
    public android.os.IBinder asBinder() {
      return null;
    }
  }
  /** Local-side IPC implementation stub class. */
  public static abstract class Stub extends android.os.Binder implements com.kymjs.ai.custard.terminal.ITerminalService
  {
    /** Construct the stub at attach it to the interface. */
    @SuppressWarnings("this-escape")
    public Stub()
    {
      this.attachInterface(this, DESCRIPTOR);
    }
    /**
     * Cast an IBinder object into an com.kymjs.ai.custard.terminal.ITerminalService interface,
     * generating a proxy if needed.
     */
    public static com.kymjs.ai.custard.terminal.ITerminalService asInterface(android.os.IBinder obj)
    {
      if ((obj==null)) {
        return null;
      }
      android.os.IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
      if (((iin!=null)&&(iin instanceof com.kymjs.ai.custard.terminal.ITerminalService))) {
        return ((com.kymjs.ai.custard.terminal.ITerminalService)iin);
      }
      return new com.kymjs.ai.custard.terminal.ITerminalService.Stub.Proxy(obj);
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
        case TRANSACTION_createSession:
        {
          java.lang.String _result = this.createSession();
          reply.writeNoException();
          reply.writeString(_result);
          break;
        }
        case TRANSACTION_switchToSession:
        {
          java.lang.String _arg0;
          _arg0 = data.readString();
          this.switchToSession(_arg0);
          reply.writeNoException();
          break;
        }
        case TRANSACTION_closeSession:
        {
          java.lang.String _arg0;
          _arg0 = data.readString();
          this.closeSession(_arg0);
          reply.writeNoException();
          break;
        }
        case TRANSACTION_sendCommand:
        {
          java.lang.String _arg0;
          _arg0 = data.readString();
          java.lang.String _result = this.sendCommand(_arg0);
          reply.writeNoException();
          reply.writeString(_result);
          break;
        }
        case TRANSACTION_sendInterruptSignal:
        {
          this.sendInterruptSignal();
          reply.writeNoException();
          break;
        }
        case TRANSACTION_registerCallback:
        {
          com.kymjs.ai.custard.terminal.ITerminalCallback _arg0;
          _arg0 = com.kymjs.ai.custard.terminal.ITerminalCallback.Stub.asInterface(data.readStrongBinder());
          this.registerCallback(_arg0);
          reply.writeNoException();
          break;
        }
        case TRANSACTION_unregisterCallback:
        {
          com.kymjs.ai.custard.terminal.ITerminalCallback _arg0;
          _arg0 = com.kymjs.ai.custard.terminal.ITerminalCallback.Stub.asInterface(data.readStrongBinder());
          this.unregisterCallback(_arg0);
          reply.writeNoException();
          break;
        }
        case TRANSACTION_requestStateUpdate:
        {
          this.requestStateUpdate();
          reply.writeNoException();
          break;
        }
        default:
        {
          return super.onTransact(code, data, reply, flags);
        }
      }
      return true;
    }
    private static class Proxy implements com.kymjs.ai.custard.terminal.ITerminalService
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
      @Override public java.lang.String createSession() throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        java.lang.String _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          boolean _status = mRemote.transact(Stub.TRANSACTION_createSession, _data, _reply, 0);
          _reply.readException();
          _result = _reply.readString();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      @Override public void switchToSession(java.lang.String sessionId) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeString(sessionId);
          boolean _status = mRemote.transact(Stub.TRANSACTION_switchToSession, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
      @Override public void closeSession(java.lang.String sessionId) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeString(sessionId);
          boolean _status = mRemote.transact(Stub.TRANSACTION_closeSession, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
      @Override public java.lang.String sendCommand(java.lang.String command) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        java.lang.String _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeString(command);
          boolean _status = mRemote.transact(Stub.TRANSACTION_sendCommand, _data, _reply, 0);
          _reply.readException();
          _result = _reply.readString();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      @Override public void sendInterruptSignal() throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          boolean _status = mRemote.transact(Stub.TRANSACTION_sendInterruptSignal, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
      @Override public void registerCallback(com.kymjs.ai.custard.terminal.ITerminalCallback callback) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeStrongInterface(callback);
          boolean _status = mRemote.transact(Stub.TRANSACTION_registerCallback, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
      @Override public void unregisterCallback(com.kymjs.ai.custard.terminal.ITerminalCallback callback) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeStrongInterface(callback);
          boolean _status = mRemote.transact(Stub.TRANSACTION_unregisterCallback, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
      @Override public void requestStateUpdate() throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          boolean _status = mRemote.transact(Stub.TRANSACTION_requestStateUpdate, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
    }
    static final int TRANSACTION_createSession = (android.os.IBinder.FIRST_CALL_TRANSACTION + 0);
    static final int TRANSACTION_switchToSession = (android.os.IBinder.FIRST_CALL_TRANSACTION + 1);
    static final int TRANSACTION_closeSession = (android.os.IBinder.FIRST_CALL_TRANSACTION + 2);
    static final int TRANSACTION_sendCommand = (android.os.IBinder.FIRST_CALL_TRANSACTION + 3);
    static final int TRANSACTION_sendInterruptSignal = (android.os.IBinder.FIRST_CALL_TRANSACTION + 4);
    static final int TRANSACTION_registerCallback = (android.os.IBinder.FIRST_CALL_TRANSACTION + 5);
    static final int TRANSACTION_unregisterCallback = (android.os.IBinder.FIRST_CALL_TRANSACTION + 6);
    static final int TRANSACTION_requestStateUpdate = (android.os.IBinder.FIRST_CALL_TRANSACTION + 7);
  }
  /** @hide */
  public static final java.lang.String DESCRIPTOR = "com.kymjs.ai.custard.terminal.ITerminalService";
  public java.lang.String createSession() throws android.os.RemoteException;
  public void switchToSession(java.lang.String sessionId) throws android.os.RemoteException;
  public void closeSession(java.lang.String sessionId) throws android.os.RemoteException;
  public java.lang.String sendCommand(java.lang.String command) throws android.os.RemoteException;
  public void sendInterruptSignal() throws android.os.RemoteException;
  public void registerCallback(com.kymjs.ai.custard.terminal.ITerminalCallback callback) throws android.os.RemoteException;
  public void unregisterCallback(com.kymjs.ai.custard.terminal.ITerminalCallback callback) throws android.os.RemoteException;
  public void requestStateUpdate() throws android.os.RemoteException;
}
