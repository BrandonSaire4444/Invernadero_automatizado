import asyncio
import json
import serial
import os
from fastapi import FastAPI, WebSocket, WebSocketDisconnect, HTTPException, Depends
from fastapi.security import OAuth2PasswordBearer, OAuth2PasswordRequestForm
from pydantic import BaseModel
from cassandra.cluster import Cluster
from datetime import datetime

app = FastAPI(title="hidroponia_pro API", version="2.0")

# --- CONEXIÓN CASSANDRA (DOCKER LOCAL) ---
cluster = Cluster(['127.0.0.1'])
session = cluster.connect('hidroponia_pro')

# --- MODELOS DE SEGURIDAD ---
class Token(BaseModel):
    access_token: str
    token_type: str

# --- ADMINISTRADOR DE CONEXIONES WEBSOCKET ---
class ConnectionManager:
    def __init__(self):
        self.active_connections: list[WebSocket] = []

    async def connect(self, websocket: WebSocket):
        await websocket.accept()
        self.active_connections.append(websocket)

    def disconnect(self, websocket: WebSocket):
        if websocket in self.active_connections:
            self.active_connections.remove(websocket)

    async def send_personal_message(self, message: str, websocket: WebSocket):
        await websocket.send_text(message)

manager = ConnectionManager()

# --- LÓGICA DE REGULACIÓN (TRADUCIDA A HILO NO BLOQUEANTE) ---
def evaluar_regulacion_sync(temp, hum, ph, luz):
    """Ejecuta de forma aislada las consultas pesadas de Cassandra para no trabar Asyncio"""
    query = "SELECT temp_min, temp_max, hum_min, hum_max, ph_min, ph_max, luz_min, luz_max FROM config_cultivos WHERE id_cultivo = %s"
    config = session.execute(query, ('lechuga_01',)).one()
    
    if not config:
        t_min, t_max, h_min, h_max, p_min, p_max, l_min, l_max = 18.0, 26.0, 40.0, 70.0, 5.5, 6.5, 40.0, 80.0
    else:
        t_min, t_max = config.temp_min, config.temp_max
        h_min, h_max = config.hum_min, config.hum_max
        p_min, p_max = config.ph_min, config.ph_max
        l_min, l_max = config.luz_min, config.luz_max

    cmd = ""
    cmd += "001" if temp < t_min else ("100" if temp > t_max else "010")
    cmd += "001" if hum < h_min else ("100" if hum > h_max else "010")
    cmd += "001" if ph < p_min else ("100" if ph > p_max else "010")
    cmd += "001" if luz < l_min else ("100" if luz > l_max else "010")
    
    ahora = datetime.now()
    u_query = "INSERT INTO estado_actuadores (componente, estado, ultima_actualizacion) VALUES (%s, %s, %s)"
    
    # Batch de inserción rápida de estados
    session.execute(u_query, ('actuador_temp', 'AZUL' if temp < t_min else ('ROJO' if temp > t_max else 'VERDE'), ahora))
    session.execute(u_query, ('actuador_hum', 'AZUL' if hum < h_min else ('ROJO' if hum > h_max else 'VERDE'), ahora))
    session.execute(u_query, ('actuador_ph', 'AZUL' if ph < p_min else ('ROJO' if ph > p_max else 'VERDE'), ahora))
    session.execute(u_query, ('actuador_luz', 'AZUL' if luz < l_min else ('ROJO' if luz > l_max else 'VERDE'), ahora))
    
    return cmd

def cmd_to_json(cmd):
    def mapear(segmento):
        if segmento == "001": return "AZUL"
        if segmento == "100": return "ROJO"
        return "VERDE"
    return {
        "actuador_temp": mapear(cmd[0:3]),
        "actuador_hum": mapear(cmd[3:6]),
        "actuador_ph": mapear(cmd[6:9]),
        "actuador_luz": mapear(cmd[9:12])
    }

# --- ENDPOINT DE AUTENTICACIÓN ---
@app.post("/api/login", response_model=Token)
async def login(form_data: OAuth2PasswordRequestForm = Depends()):
    query = "SELECT password_hash FROM usuarios WHERE username = %s"
    resultado = session.execute(query, (form_data.username,)).one()
    
    if resultado and resultado.password_hash == form_data.password:
        return {"access_token": form_data.username, "token_type": "bearer"}
    
    raise HTTPException(status_code=400, detail="Usuario o contraseña incorrectos")

# --- HILO ASÍNCRONO: ARDUINO SERIAL ---
async def escuchar_arduino():
    try:
        if not os.path.exists('/dev/ttyACM0'):
            print("[SERIAL] Alerta: /dev/ttyACM0 no detectado. Modo simulación activo.")
            return

        puerto = serial.Serial('/dev/ttyACM0', 115200, timeout=1)
        puerto.setDTR(False)
        await asyncio.sleep(1)
        puerto.flushInput()
        puerto.setDTR(True)
        await asyncio.sleep(2)
        print("[SERIAL] Conectado exitosamente al Arduino en /dev/ttyACM0")
        
        while True:
            if puerto.in_waiting > 0:
                linea = puerto.readline().decode('utf-8', errors='ignore').strip()
                partes = linea.split(',')
                if len(partes) == 5 and partes[0] == "REAL":
                    temp = float(partes[1])
                    hum  = float(partes[2])
                    ph   = float(partes[3])
                    luz  = float(partes[4])
                    
                    # Ejecutar la lógica síncrona de base de datos en un hilo seguro
                    comando_bits = await asyncio.to_thread(evaluar_regulacion_sync, temp, hum, ph, luz)
                    puerto.write((comando_bits + "\n").encode())
                    
                    ahora = datetime.now()
                    query = "INSERT INTO lecturas_sensores (origen, fecha_hora, temperatura, humedad, ph, luz) VALUES (%s, %s, %s, %s, %s, %s)"
                    await asyncio.to_thread(session.execute, query, ('INVERNADERO_REAL', ahora, temp, hum, ph, luz))
                    
            await asyncio.sleep(0.1)
    except Exception as e:
        print(f"[SERIAL] Error crítico en hardware: {e}")

@app.on_event("startup")
async def startup_event():
    asyncio.create_task(escuchar_arduino())

# --- ENDPOINT WEBSOCKET EXCELENCIA OPERACIONAL ---
# --- ENDPOINT WEBSOCKET BLINDADO CONTRA LOOP INFINITO ---
# --- FUNCIÓN AUXILIAR SÍNCRONA PARA CONSULTAR CASSANDRA EN ARRANQUE ---
def obtener_datos_bienvenida_sync():
    try:
        query = "SELECT id_cultivo, temp_min, temp_max FROM config_cultivos WHERE id_cultivo = %s"
        config = session.execute(query, ('lechuga_01',)).one()
        if config:
            return {
                "cultivo": f"🌱 Monitoreo Activo: {config.id_cultivo.upper()}",
                "rangos": f"Rangos Óptimos Temp: {config.temp_min}°C - {config.temp_max}°C (Cassandra)"
            }
    except Exception:
        pass
    return {
        "cultivo": "🌱 Monitoreo Activo: LECHUGA (Por Defecto)",
        "rangos": "Rangos Estándar Sincronizados con el Diccionario de Datos"
    }

# --- ENDPOINT WEBSOCKET EXCELENCIA OPERACIONAL CONTRA LOOP INFINITO ---
@app.websocket("/ws/invernadero")
async def websocket_endpoint(websocket: WebSocket):
    await manager.connect(websocket)
    print("\n🟢 [WEBSOCKET] Canal abierto. Esperando telemetría...")
    
    try:
        # BUENA PRÁCTICA: Enviamos el estado inicial del cultivo al conectar para actualizar la tarjeta azul de Android
        info_cultivo = await asyncio.to_thread(obtener_datos_bienvenida_sync)
        init_payload = {
            "txt_verdura": info_cultivo["cultivo"],
            "txt_rangos": info_cultivo["rangos"],
            "actuador_temp": "VERDE",
            "actuador_hum": "VERDE",
            "actuador_ph": "VERDE",
            "actuador_luz": "VERDE"
        }
        await websocket.send_text(json.dumps(init_payload))

        while True:
            try:
                data = await websocket.receive_text()
                payload = json.loads(data)
                
                temp = float(payload["temp"])
                hum = float(payload["hum"])
                ph = float(payload["ph"])
                luz = float(payload["luz"])
                
                print(f"📥 [TELEMETRÍA] Android -> Temp: {temp}°C | Hum: {hum}% | pH: {ph} | Luz: {luz}%")
                
                ahora = datetime.now()
                query = "INSERT INTO lecturas_sensores (origen, fecha_hora, temperatura, humedad, ph, luz) VALUES (%s, %s, %s, %s, %s, %s)"
                await asyncio.to_thread(session.execute, query, ('SIMULADOR_ANDROID', ahora, temp, hum, ph, luz))
                
                comando_bits = await asyncio.to_thread(evaluar_regulacion_sync, temp, hum, ph, luz)
                respuesta_json = cmd_to_json(comando_bits)
                
                await manager.send_personal_message(json.dumps(respuesta_json), websocket)
                
            except (WebSocketDisconnect, ConnectionResetError):
                print("🔌 [WEBSOCKET] Conexión cerrada por el cliente de forma abrupta. Liberando recursos.")
                break
            except KeyError as e:
                print(f"⚠️ [MALEABILIDAD] Estructura JSON inválida: {e}")
            except Exception as e:
                print(f"❌ [ERROR CONTROLADO] Detalle: {str(e)}")
                if "NoneType" in str(e) or "WebSocket" in str(e):
                    break
    finally:
        manager.disconnect(websocket)
        print("🧹 [INFRAESTRUCTURA] Memoria del socket limpiada exitosamente.")

@app.get("/")
def read_root():
    return {"status": "Servidor HidroponiaPro Operacional", "motor": "FastAPI"}