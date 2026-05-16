import asyncio
import json
import serial
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
        self.active_connections.remove(websocket)

    async def send_personal_message(self, message: str, websocket: WebSocket):
        await websocket.send_text(message)

manager = ConnectionManager()

# --- LÓGICA DE REGULACIÓN DINÁMICA CONSULTANDO CASSANDRA ---
def evaluar_regulacion(temp, hum, ph, luz):
    # Consulta los límites operativos cargados en la base de datos para la lechuga
    query = "SELECT temp_min, temp_max, hum_min, hum_max, ph_min, ph_max, luz_min, luz_max FROM config_cultivos WHERE id_cultivo = %s"
    config = session.execute(query, ('lechuga_01',)).one()
    
    # Parámetros por seguridad si no existiera registro
    if not config:
        t_min, t_max, h_min, h_max, p_min, p_max, l_min, l_max = 18.0, 26.0, 40.0, 70.0, 5.5, 6.5, 40.0, 80.0
    else:
        t_min, t_max = config.temp_min, config.temp_max
        h_min, h_max = config.hum_min, config.hum_max
        p_min, p_max = config.ph_min, config.ph_max
        l_min, l_max = config.luz_min, config.luz_max

    cmd = ""
    # Temperatura -> Azul (Bajo), Rojo (Alto), Verde (Óptimo)
    cmd += "001" if temp < t_min else ("100" if temp > t_max else "010")
    # Humedad
    cmd += "001" if hum < h_min else ("100" if hum > h_max else "010")
    # pH
    cmd += "001" if ph < p_min else ("100" if ph > p_max else "010")
    # Luz
    cmd += "001" if luz < l_min else ("100" if luz > l_max else "010")
    
    # Persistir los estados calculados en la tabla de actuadores
    ahora = datetime.now()
    u_query = "INSERT INTO estado_actuadores (componente, estado, ultima_actualizacion) VALUES (%s, %s, %s)"
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

# --- ENDPOINT DE AUTENTICACIÓN (LOGIN REAL CON CASSANDRA) ---
@app.post("/api/login", response_model=Token)
async def login(form_data: OAuth2PasswordRequestForm = Depends()):
    query = "SELECT password_hash FROM usuarios WHERE username = %s"
    resultado = session.execute(query, (form_data.username,)).one()
    
    if resultado and resultado.password_hash == form_data.password:
        return {"access_token": form_data.username, "token_type": "bearer"}
    
    raise HTTPException(status_code=400, detail="Usuario o contraseña incorrectos")

# --- HILO ASÍNCRONO: LECTURA DEL ARDUINO FÍSICO (SERIAL USB) ---
async def escuchar_arduino():
    try:
        # Inicialización estricta del bus Serial
        puerto = serial.Serial('/dev/ttyACM0', 115200, timeout=1)
        
        # CORRECCIÓN 1: Forzar reinicio DTR/RTS para despertar la transmisión de datos del hardware
        puerto.setDTR(False)
        await asyncio.sleep(1)
        puerto.flushInput()
        puerto.setDTR(True)
        
        await asyncio.sleep(2)  # Retardo de estabilización
        print("[SERIAL] Conectado exitosamente al Arduino en /dev/ttyACM0")
        
        while True:
            if puerto.in_waiting > 0:
                linea = puerto.readline().decode('utf-8', errors='ignore').strip()
                
                # Depuración en tiempo real en consola
                print(f"[DEBUG SERIAL] Procesando buffer: {linea}")
                
                partes = linea.split(',')
                if len(partes) == 5 and partes[0] == "REAL":
                    # CORRECCIÓN 2: Mapeo explícito a float nativo compatible con controladores CQL
                    temp = float(partes[1])
                    hum  = float(partes[2])
                    ph   = float(partes[3])
                    luz  = float(partes[4])
                    
                    comando_bits = evaluar_regulacion(temp, hum, ph, luz)
                    
                    # Escritura directa de vuelta hacia el hardware
                    puerto.write((comando_bits + "\n").encode())
                    
                    # Persistencia de Telemetría histórica
                    ahora = datetime.now()
                    query = "INSERT INTO lecturas_sensores (origen, fecha_hora, temperatura, humedad, ph, luz) VALUES (%s, %s, %s, %s, %s, %s)"
                    session.execute(query, ('INVERNADERO_REAL', ahora, temp, hum, ph, luz))
                    
            await asyncio.sleep(0.1)
    except Exception as e:
        print(f"[SERIAL] Error crítico en el subproceso del hardware: {e}")

@app.on_event("startup")
async def startup_event():
    asyncio.create_task(escuchar_arduino())

# --- ENDPOINT WEBSOCKET: CANAL EN TIEMPO REAL PARA ANDROID ---
@app.websocket("/ws/invernadero")
async def websocket_endpoint(websocket: WebSocket):
    await manager.connect(websocket)
    try:
        while True:
            data = await websocket.receive_text()
            payload = json.loads(data)
            
            temp = float(payload["temp"])
            hum = float(payload["hum"])
            ph = float(payload["ph"])
            luz = float(payload["luz"])
            
            ahora = datetime.now()
            query = "INSERT INTO lecturas_sensores (origen, fecha_hora, temperatura, humedad, ph, luz) VALUES (%s, %s, %s, %s, %s, %s)"
            session.execute(query, ('SIMULADOR_ANDROID', ahora, temp, hum, ph, luz))
            
            comando_bits = evaluar_regulacion(temp, hum, ph, luz)
            respuesta_json = cmd_to_json(comando_bits)
            
            await manager.send_personal_message(json.dumps(respuesta_json), websocket)
            
    except WebSocketDisconnect:
        manager.disconnect(websocket)

# --- ENDPOINT DE PRUEBA GENERAL ---
@app.get("/")
def read_root():
    return {"status": "Servidor HidroponiaPro Operacional", "motor": "FastAPI"}