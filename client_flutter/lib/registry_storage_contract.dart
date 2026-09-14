abstract interface class RegistryStorage {
  Future<Object?> read();
  Future<void> write(Map<String, Object> value);
}
