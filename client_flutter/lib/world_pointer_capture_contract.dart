abstract interface class WorldPointerCapture {
  bool get supported;
  void request();
  void release();
  void dispose();
}

typedef PointerCaptureFactory =
    WorldPointerCapture Function(
      void Function(bool) onChanged,
      void Function(double, double) onLook,
    );
