import 'package:flutter/material.dart';
import 'connection.dart';

void main() => runApp(const ObserverApp());

class ObserverApp extends StatelessWidget {
  const ObserverApp({super.key, this.connection});
  final ObserverConnection? connection;
  @override
  Widget build(BuildContext context) => MaterialApp(
    title: 'Totem Observer',
    debugShowCheckedModeBanner: false,
    theme: ThemeData(
      fontFamily: 'NotoSansTC',
      useMaterial3: false,
      brightness: Brightness.dark,
      scaffoldBackgroundColor: const Color(0xff242424),
      colorScheme: const ColorScheme.dark(
        primary: Color(0xffb4d38a),
        surface: Color(0xff353535),
      ),
      inputDecorationTheme: const InputDecorationTheme(
        filled: true,
        fillColor: Color(0xff191919),
        border: OutlineInputBorder(borderRadius: BorderRadius.zero),
      ),
      elevatedButtonTheme: ElevatedButtonThemeData(
        style: ElevatedButton.styleFrom(
          backgroundColor: const Color(0xff555555),
          foregroundColor: Colors.white,
          minimumSize: const Size(176, 48),
          elevation: 0,
          shape: const RoundedRectangleBorder(
            borderRadius: BorderRadius.zero,
            side: BorderSide(color: Color(0xffaaaaaa)),
          ),
        ),
      ),
    ),
    home: ConnectionPage(connection: connection),
  );
}

class ConnectionPage extends StatefulWidget {
  const ConnectionPage({super.key, this.connection});
  final ObserverConnection? connection;
  @override
  State<ConnectionPage> createState() => _ConnectionPageState();
}

class _ConnectionPageState extends State<ConnectionPage> {
  late final ObserverConnection connection =
      widget.connection ?? ObserverConnection();
  final address = TextEditingController(
    text: 'ws://127.0.0.1:25580/observer/bridge',
  );
  final username = TextEditingController();
  final password = TextEditingController();
  @override
  void dispose() {
    address.dispose();
    username.dispose();
    password.dispose();
    if (widget.connection == null) connection.dispose();
    super.dispose();
  }

  void submit(bool register) {
    connection.authenticate(
      address.text,
      username.text.trim(),
      password.text,
      register: register,
    );
    password.clear();
    FocusScope.of(context).unfocus();
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    body: SafeArea(
      child: Center(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(16),
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 448),
            child: AnimatedBuilder(
              animation: connection,
              builder: (context, _) {
                final online = connection.phase == ConnectionPhase.connected;
                final busy = connection.phase == ConnectionPhase.connecting;
                return Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    const Text(
                      'Totem Observer',
                      textAlign: TextAlign.center,
                      style: TextStyle(
                        fontSize: 28,
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                    const SizedBox(height: 8),
                    const Text(
                      '多人連線',
                      textAlign: TextAlign.center,
                      style: TextStyle(fontSize: 18),
                    ),
                    const SizedBox(height: 24),
                    Semantics(
                      liveRegion: true,
                      child: Text(
                        connection.status,
                        textAlign: TextAlign.center,
                        style: TextStyle(
                          color: online
                              ? const Color(0xffb4d38a)
                              : Colors.white,
                        ),
                      ),
                    ),
                    const SizedBox(height: 16),
                    if (online) ...[
                      Text(
                        '登入帳號：${connection.account}',
                        textAlign: TextAlign.center,
                      ),
                      const SizedBox(height: 8),
                      Text(
                        '已收到 ${connection.replies} 次連線回應',
                        textAlign: TextAlign.center,
                      ),
                      const SizedBox(height: 16),
                      const Text(
                        '目前可登入並確認連線。進入世界功能尚在開發中。',
                        textAlign: TextAlign.center,
                      ),
                      const SizedBox(height: 24),
                      ElevatedButton(
                        onPressed: connection.disconnect,
                        child: const Text('登出'),
                      ),
                    ] else ...[
                      const Text('目前僅提供本機連線測試。'),
                      const SizedBox(height: 16),
                      TextField(
                        controller: address,
                        enabled: !busy,
                        autocorrect: false,
                        decoration: const InputDecoration(labelText: '伺服器位址'),
                        textInputAction: TextInputAction.next,
                      ),
                      const SizedBox(height: 16),
                      TextField(
                        controller: username,
                        enabled: !busy,
                        autocorrect: false,
                        enableSuggestions: false,
                        decoration: const InputDecoration(
                          labelText: '帳號',
                          helperText: '3–24 個小寫英數或底線',
                        ),
                        textInputAction: TextInputAction.next,
                      ),
                      const SizedBox(height: 16),
                      TextField(
                        controller: password,
                        enabled: !busy,
                        obscureText: true,
                        autocorrect: false,
                        enableSuggestions: false,
                        maxLength: 128,
                        decoration: const InputDecoration(
                          labelText: '密碼',
                          helperText: '至少 12 個字元',
                        ),
                        onSubmitted: busy ? null : (_) => submit(false),
                      ),
                      const SizedBox(height: 16),
                      ElevatedButton(
                        onPressed: busy ? null : () => submit(false),
                        child: Text(busy ? '連線中…' : '登入'),
                      ),
                      const SizedBox(height: 8),
                      OutlinedButton(
                        onPressed: busy ? null : () => submit(true),
                        child: const Text('建立帳號'),
                      ),
                      if (busy)
                        TextButton(
                          onPressed: connection.disconnect,
                          child: const Text('取消'),
                        ),
                    ],
                  ],
                );
              },
            ),
          ),
        ),
      ),
    ),
  );
}
