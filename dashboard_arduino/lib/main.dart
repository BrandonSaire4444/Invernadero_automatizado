import 'dart:async';
import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:http/http.dart' as http;

void main() {
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      home: const LoginScreen(), // ← Arranca en Login, no en Dashboard
    );
  }
}

// ─────────────────────────────────────
// PANTALLA DE LOGIN
// ─────────────────────────────────────
class LoginScreen extends StatefulWidget {
  const LoginScreen({super.key});

  @override
  State<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends State<LoginScreen> {
  final _userController = TextEditingController();
  final _passController = TextEditingController();
  bool _loading = false;
  String _error = '';

  // ⚠️ Cambia esta IP por la de tu PC donde corre Django
  final String djangoUrl = "http://localhost:8000/api/login/";
  Future<void> _login() async {
    setState(() {
      _loading = true;
      _error = '';
    });

    try {
      final response = await http.post(
        Uri.parse(djangoUrl),
        headers: {'Content-Type': 'application/json'},
        body: jsonEncode({
          'username': _userController.text.trim(),
          'password': _passController.text.trim(),
        }),
      );

      final data = jsonDecode(response.body);

      if (data['status'] == 'success') {
        final token = data['token'];

        // Navega al Dashboard pasando el token
        Navigator.pushReplacement(
          context,
          MaterialPageRoute(builder: (_) => Dashboard(token: token)),
        );
      } else {
        setState(() {
          _error = data['message'] ?? 'Error desconocido';
        });
      }
    } catch (e) {
      setState(() {
        _error = 'No se pudo conectar con el servidor';
      });
    } finally {
      setState(() => _loading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.black,
      body: Center(
        child: Padding(
          padding: const EdgeInsets.all(32),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Text(
                'Invernadero',
                style: TextStyle(
                  color: Colors.white,
                  fontSize: 28,
                  fontWeight: FontWeight.bold,
                ),
              ),
              const SizedBox(height: 40),
              TextField(
                controller: _userController,
                style: const TextStyle(color: Colors.white),
                decoration: _inputDecoration('Usuario'),
              ),
              const SizedBox(height: 16),
              TextField(
                controller: _passController,
                obscureText: true,
                style: const TextStyle(color: Colors.white),
                decoration: _inputDecoration('Contraseña'),
              ),
              const SizedBox(height: 24),
              if (_error.isNotEmpty)
                Text(_error, style: const TextStyle(color: Colors.redAccent)),
              const SizedBox(height: 8),
              SizedBox(
                width: double.infinity,
                child: ElevatedButton(
                  onPressed: _loading ? null : _login,
                  style: ElevatedButton.styleFrom(
                    backgroundColor: Colors.green,
                    padding: const EdgeInsets.symmetric(vertical: 16),
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(12),
                    ),
                  ),
                  child: _loading
                      ? const CircularProgressIndicator(color: Colors.white)
                      : const Text(
                          'Entrar',
                          style: TextStyle(fontSize: 16, color: Colors.white),
                        ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  InputDecoration _inputDecoration(String label) {
    return InputDecoration(
      labelText: label,
      labelStyle: const TextStyle(color: Colors.white70),
      filled: true,
      fillColor: Colors.grey[900],
      border: OutlineInputBorder(borderRadius: BorderRadius.circular(12)),
      enabledBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(12),
        borderSide: const BorderSide(color: Colors.white24),
      ),
    );
  }
}

// ─────────────────────────────────────
// DASHBOARD (ya lo tenías, solo se agrega el token)
// ─────────────────────────────────────
class Dashboard extends StatefulWidget {
  final String token; // ← recibe el token del login
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

  // ⚠️ Cambia esta IP por la de tu ESP32/Arduino (ya la tenías)
  final String urlBase = "http://172.20.10.2";

  @override
  void initState() {
    super.initState();
    timer = Timer.periodic(const Duration(seconds: 3), (timer) {
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
      print("Error conexión: $e");
    }
  }

  Future<void> enviarComando(String comando) async {
    try {
      await http.get(Uri.parse("$urlBase/$comando"));
    } catch (e) {
      print("Error enviando comando");
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

  // Cerrar sesión → regresa al Login
  void _logout() {
    timer?.cancel();
    Navigator.pushReplacement(
      context,
      MaterialPageRoute(builder: (_) => const LoginScreen()),
    );
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
                  colors: [color.withOpacity(0.2), color],
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
            onPressed: _logout, // ← botón de cerrar sesión
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
