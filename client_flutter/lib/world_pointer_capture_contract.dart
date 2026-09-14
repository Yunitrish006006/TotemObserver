abstract interface class WorldPointerCapture {
  bool get supported;
  set onUse(void Function()? callback);
  set onDestroy(void Function(bool held)? callback);
  void request();
  void release();
  void dispose();
}

typedef PointerCaptureFactory =
    WorldPointerCapture Function(
      void Function(bool) onChanged,
      void Function(double, double) onLook,
    );
