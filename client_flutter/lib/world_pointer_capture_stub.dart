import 'world_pointer_capture_contract.dart';

WorldPointerCapture createWorldPointerCapture(
  void Function(bool) onChanged,
  void Function(double, double) onLook,
) => _Unavailable();

class _Unavailable implements WorldPointerCapture {
  @override
  bool get supported => false;
  @override
  void request() {}
  @override
  void release() {}
  @override
  void dispose() {}
}
