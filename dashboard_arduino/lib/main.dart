import 'dart:async';
import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter/foundation.dart';
import 'package:http/http.dart' as http;
import 'package:shared_preferences/shared_preferences.dart';
// ignore: avoid_web_libraries_in_flutter
import 'dart:html' as html;

void main() async {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      home: const AuthGate(),
    );
  }
}

// ─────────────────────────────────────
// AUTH GATE
// ─────────────────────────────────────
class AuthGate extends StatefulWidget {
  const AuthGate({super.key});

  @override
  State<AuthGate> createState() => _AuthGateState();
}

class _AuthGateState extends State<AuthGate> {
  @override
  void initState() {
    super.initState();
    _checkSession();
  }

  Future<void> _checkSession() async {
    String? token;

    if (kIsWeb) {
      // Lee la URL completa del navegador
      final fullUrl = html.window.location.href;
      debugPrint('URL actual: $fullUrl');

      // Busca el token en la URL — funciona con # y sin #
      // Ejemplos:
      //   http://localhost:8080/#/dashboard?token=abc
      //   http://localhost:8080/?token=abc
      Uri uri;
      try {
        // Intenta parsear la parte después del #
        final hashIndex = fullUrl.indexOf('#');
        if (hashIndex != -1) {
          final afterHash = fullUrl.substring(hashIndex + 1);
          uri = Uri.parse('http://dummy$afterHash');
        } else {
          uri = Uri.parse(fullUrl);
        }
        token = uri.queryParameters['token'];
        debugPrint('Token encontrado en URL: $token');
      } catch (e) {
        debugPrint('Error parseando URL: $e');
      }

      if (token != null && token.isNotEmpty) {
        // Guardar token y limpiar URL
        final prefs = await SharedPreferences.getInstance();
        await prefs.setString('auth_token', token);
        html.window.history.replaceState({}, '', '/');
      }
    }

    // Si no vino en URL, buscar en storage
    if (token == null || token.isEmpty) {
      final prefs = await SharedPreferences.getInstance();
      token = prefs.getString('auth_token');
      debugPrint('Token desde storage: $token');
    }

    if (!mounted) return;

    if (token != null && token.isNotEmpty) {
      Navigator.pushReplacement(
        context,
        MaterialPageRoute(builder: (_) => Dashboard(token: token!)),
      );
    } else {
      // Sin token → redirigir al login de Django
      if (kIsWeb) {
        html.window.location.href = 'http://172.20.10.10:8000/login/';
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return const Scaffold(
      backgroundColor: Colors.black,
      body: Center(child: CircularProgressIndicator(color: Colors.green)),
    );
  }
}

// ─────────────────────────────────────
// SERVICIO DE AUTENTICACIÓN
// ─────────────────────────────────────
class AuthService {
  static Future<void> logout() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.remove('auth_token');
  }

  static Future<String?> getToken() async {
    final prefs = await SharedPreferences.getInstance();
    return prefs.getString('auth_token');
  }
}

// ─────────────────────────────────────
// DASHBOARD
// ─────────────────────────────────────
class Dashboard extends StatefulWidget {
  final String token;
  const Dashboard({super.key, required this.token});

  @override
  _DashboardState createState() => _DashboardState();
}

class _DashboardState extends State<Dashboard> {
  double temperatura = 0;
  double humedad = 0;
  bool bomba = false;
  bool ventilador = false;
  bool luces = false;
  Timer? timer;

  final String urlBase = "http://172.20.10.2";

  @override
  void initState() {
    super.initState();
    timer = Timer.periodic(const Duration(seconds: 3), (_) {
      obtenerDatos();
    });
  }

  Future<void> obtenerDatos() async {
    try {
      final response = await http.get(Uri.parse("$urlBase/data"));
      if (response.statusCode == 200) {
        final jsonData = json.decode(response.body);
        if (jsonData["data"] != null) {
          String raw = jsonData["data"].toString().trim();
          final regexTemp = RegExp(r'T:(\d+\.?\d*)');
          final regexHum = RegExp(r'H:(\d+\.?\d*)');
          final matchTemp = regexTemp.firstMatch(raw);
          final matchHum = regexHum.firstMatch(raw);
          if (matchTemp != null && matchHum != null) {
            setState(() {
              temperatura = double.parse(matchTemp.group(1)!);
              humedad = double.parse(matchHum.group(1)!);
            });
          }
        }
      }
    } catch (e) {
      debugPrint("Error conexión: $e");
    }
  }

  Future<void> enviarComando(String comando) async {
    try {
      await http.get(Uri.parse("$urlBase/$comando"));
    } catch (e) {
      debugPrint("Error enviando comando");
    }
  }

  void toggleBomba() {
    setState(() => bomba = !bomba);
    enviarComando(bomba ? "bomba_on" : "bomba_off");
  }

  void toggleVentilador() {
    setState(() => ventilador = !ventilador);
    enviarComando(ventilador ? "vent_on" : "vent_off");
  }

  void toggleLuces() {
    setState(() => luces = !luces);
    enviarComando(luces ? "luz_on" : "luz_off");
  }

  Future<void> _logout() async {
    timer?.cancel();
    await AuthService.logout();
    if (kIsWeb) {
      html.window.location.href = 'http://172.20.10.10:8000/login/';
    }
  }

  Widget tarjetaSensor(
    String titulo,
    double valor,
    String unidad,
    Color color,
  ) {
    return Expanded(
      child: Container(
        margin: const EdgeInsets.all(8),
        padding: const EdgeInsets.all(16),
        decoration: BoxDecoration(
          color: Colors.black87,
          borderRadius: BorderRadius.circular(20),
        ),
        child: Column(
          children: [
            Text(titulo, style: const TextStyle(color: Colors.white70)),
            const SizedBox(height: 20),
            Container(
              height: 150,
              width: 30,
              decoration: BoxDecoration(
                borderRadius: BorderRadius.circular(20),
                gradient: LinearGradient(
                  colors: [color.withValues(alpha: 0.2), color],
                  begin: Alignment.bottomCenter,
                  end: Alignment.topCenter,
                ),
              ),
            ),
            const SizedBox(height: 20),
            Text(
              "${valor.toStringAsFixed(1)} $unidad",
              style: const TextStyle(color: Colors.white, fontSize: 20),
            ),
          ],
        ),
      ),
    );
  }

  Widget botonControl(
    String titulo,
    bool estado,
    Function() onTap,
    IconData icono,
  ) {
    return Expanded(
      child: GestureDetector(
        onTap: onTap,
        child: Container(
          margin: const EdgeInsets.all(8),
          padding: const EdgeInsets.all(16),
          decoration: BoxDecoration(
            color: estado ? Colors.green : Colors.grey[800],
            borderRadius: BorderRadius.circular(15),
          ),
          child: Column(
            children: [
              Icon(icono, color: Colors.white, size: 40),
              const SizedBox(height: 10),
              Text(titulo, style: const TextStyle(color: Colors.white)),
              Text(
                estado ? "ACTIVO" : "INACTIVO",
                style: const TextStyle(color: Colors.white70),
              ),
            ],
          ),
        ),
      ),
    );
  }

  @override
  void dispose() {
    timer?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.black,
      appBar: AppBar(
        title: const Text("Invernadero"),
        backgroundColor: Colors.black,
        actions: [
          IconButton(
            icon: const Icon(Icons.logout, color: Colors.white),
            onPressed: _logout,
            tooltip: 'Cerrar sesión',
          ),
        ],
      ),
      body: Column(
        children: [
          Row(
            children: [
              tarjetaSensor("Temperatura", temperatura, "°C", Colors.green),
              tarjetaSensor("Humedad", humedad, "%", Colors.blue),
            ],
          ),
          const SizedBox(height: 10),
          const Text("CONTROL MANUAL", style: TextStyle(color: Colors.white)),
          Row(
            children: [
              botonControl("BOMBA", bomba, toggleBomba, Icons.water),
              botonControl(
                "VENTILADOR",
                ventilador,
                toggleVentilador,
                Icons.air,
              ),
              botonControl("LUCES", luces, toggleLuces, Icons.lightbulb),
            ],
          ),
        ],
      ),
    );
  }
}
