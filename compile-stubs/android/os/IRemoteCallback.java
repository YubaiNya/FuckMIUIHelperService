package android.os;

/** Compile-only shape of AOSP IRemoteCallback; never included in the module DEX. */
public interface IRemoteCallback extends IInterface {
  void sendResult(Bundle data) throws RemoteException;

  abstract class Stub extends Binder implements IRemoteCallback {
    public IBinder asBinder() {
      return this;
    }
  }
}
