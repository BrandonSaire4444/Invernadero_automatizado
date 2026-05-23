import asyncio
import json
from fastapi import FastAPI, WebSocket, WebSocketDisconnect, HTTPException, status, Depends
from pydantic import BaseModel, ConfigDict
from cassandra.cluster import Cluster
import jwt
import bcrypt
import uvicorn
from typing import Optional

app = FastAPI(title="HidroponiaPro API", version="4.0")

# --- CONFIGURACIÓN ---
SECRET_KEY = "SISTEMAS_AUDITORIA_BOLIVIA_SECURE_KEY"
ALGORITHM = "HS256"

# --- CONEXIÓN CASSANDRA ---
cluster = Cluster(['172.18.0.2'])
session = cluster.connect('hidroponia_pro')

# --- MODELOS ---
class UsuarioRegistro(BaseModel):
    nombre: str
    username: str
    password: str

class UsuarioLogin(BaseModel):
    username: str
    password: str
    model_config = ConfigDict(extra='ignore')

class CultivoRegistro(BaseModel):
    id_cultivo: Optional[str] = None
    nombre_verdura: str
    temp_min: float
    temp_max: float
    hum_min: float
    hum_max: float
    ph_min: float
    ph_max: float
    luz_min: float
    luz_max: float

# --- GESTOR DE CONEXIONES ---
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

# --- TAREA DE FONDO PARA CASSANDRA (NO BLOQUEANTE) ---
async def guardar_datos_async(payload, estados):
    try:
        query_insert = """INSERT INTO lecturas_sensores (origen, fecha_hora, temperatura, humedad, ph, luz, act_temp, act_hum, act_ph, act_luz)
        VALUES (%s, toTimestamp(now()), %s, %s, %s, %s, %s, %s, %s, %s)"""
        
        await asyncio.to_thread(session.execute, query_insert, 
            ("SIMULADOR_ANDROID", float(payload['temp']), float(payload['hum']), float(payload['ph']), float(payload['luz']), estados['temp'], estados['hum'], estados['ph'], estados['luz']))
    except Exception as e:
        # Esto imprime el error si algo falla, pero NO detiene el WebSocket
        print(f"DEBUG: Aviso de persistencia (ignorable): {e}")

# --- LÓGICA DE CONTROL (LAZO CERRADO) ---
def calcular_bits_estado(payload, cultivo):
    def obtener_estado(valor, min_val, max_val):
        # 1. Punto objetivo (Setpoint)
        
        mid = (min_val + max_val) / 2
        # 2. Banda muerta (5% del rango, asegurando que sea al menos un valor mínimo)
        tolerancia = max((max_val - min_val) * 0.08, 0.1) 
        
        if abs(valor - mid) <= tolerancia:
            return "010" # VERDE
        elif valor < mid:
            return "001" # AZUL (Aumentar)
        else:
            return "100" # ROJO (Disminuir)

    # Convertimos los valores del payload a float para comparar con el esquema
    t = obtener_estado(float(payload.get('temp', 0)), cultivo.temp_min, cultivo.temp_max)
    h = obtener_estado(float(payload.get('hum', 0)), cultivo.hum_min, cultivo.hum_max)
    p = obtener_estado(float(payload.get('ph', 0)), cultivo.ph_min, cultivo.ph_max)
    l = obtener_estado(float(payload.get('luz', 0)), cultivo.luz_min, cultivo.luz_max)
    return t + h + p + l

def verificar_token_ws(token: str):
    try: return jwt.decode(token, SECRET_KEY, algorithms=[ALGORITHM]).get("sub")
    except: return None

# --- ENDPOINTS ---
@app.post("/api/usuarios/registrar", status_code=201)
async def registrar_usuario(usuario: UsuarioRegistro):
    hashed = bcrypt.hashpw(usuario.password.encode('utf-8'), bcrypt.gensalt())
    query = "INSERT INTO usuarios (nombre, username, password_hash) VALUES (%s, %s, %s)"
    await asyncio.to_thread(session.execute, query, (usuario.nombre, usuario.username, hashed.decode('utf-8')))
    return {"message": "Usuario registrado"}

@app.post("/api/login")
async def login(usuario: UsuarioLogin):
    query = "SELECT password_hash FROM usuarios WHERE username = %s"
    row = await asyncio.to_thread(session.execute, query, (usuario.username,))
    user = row.one()
    if not user or not bcrypt.checkpw(usuario.password.encode('utf-8'), str(user.password_hash).encode('utf-8')):
        raise HTTPException(status_code=401, detail="Credenciales incorrectas")
    token = jwt.encode({"sub": usuario.username}, SECRET_KEY, algorithm=ALGORITHM)
    return {"token": token}

@app.post("/api/cultivos/registrar", status_code=201)
async def registrar_cultivo(cultivo: CultivoRegistro):
    id_limpio = (cultivo.id_cultivo or cultivo.nombre_verdura.strip().lower().replace(" ", "_")).strip().lower()
    query = "INSERT INTO config_cultivos (id_cultivo, nombre_verdura, temp_min, temp_max, hum_min, hum_max, ph_min, ph_max, luz_min, luz_max, activo) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, true)"
    await asyncio.to_thread(session.execute, query, (id_limpio, cultivo.nombre_verdura, cultivo.temp_min, cultivo.temp_max, cultivo.hum_min, cultivo.hum_max, cultivo.ph_min, cultivo.ph_max, cultivo.luz_min, cultivo.luz_max))
    return {"message": "Cultivo registrado"}

@app.get("/api/cultivos")
async def listar_cultivos():
    rows = await asyncio.to_thread(session.execute, "SELECT id_cultivo, nombre_verdura FROM config_cultivos WHERE activo = true ALLOW FILTERING")
    return {"cultivos": [{"id_cultivo": row.id_cultivo, "nombre_verdura": row.nombre_verdura} for row in rows]}

# --- WEBSOCKET (CONTROLADOR ACTIVO) ---
@app.websocket("/ws/invernadero/{id_cultivo}")
async def websocket_endpoint(websocket: WebSocket, id_cultivo: str, token: str = None):
    await manager.connect(websocket)
    if not token or not verificar_token_ws(token):
        await websocket.close(code=status.WS_1008_POLICY_VIOLATION); return
    
    res = await asyncio.to_thread(session.execute, "SELECT * FROM config_cultivos WHERE id_cultivo = %s", (id_cultivo,))
    cultivo = res.one()
    if not cultivo: await websocket.close(); return

    try:
        while True:
            data = await websocket.receive_text()
            print(f"DEBUG: Datos recibidos: {data}")
            try:
                payload = json.loads(data)
                if not all(field in payload for field in ['temp', 'hum', 'ph', 'luz']): continue

                comando_bits = calcular_bits_estado(payload, cultivo)
                c = [comando_bits[i:i+3] for i in range(0, 12, 3)]
                estados = {
                    "temp": "VERDE" if c[0] == "010" else ("ROJO" if c[0] == "100" else "AZUL"),
                    "hum": "VERDE" if c[1] == "010" else ("ROJO" if c[1] == "100" else "AZUL"),
                    "ph": "VERDE" if c[2] == "010" else ("ROJO" if c[2] == "100" else "AZUL"),
                    "luz": "VERDE" if c[3] == "010" else ("ROJO" if c[3] == "100" else "AZUL")
                }

                # TAREA ASÍNCRONA: No usamos 'await' aquí para que el WebSocket no se bloquee
                asyncio.create_task(guardar_datos_async(payload, estados))

                respuesta = {
                    "txt_verdura": cultivo.nombre_verdura,
                    "txt_rangos": f"T:{cultivo.temp_min}-{cultivo.temp_max} | H:{cultivo.hum_min}-{cultivo.hum_max} | pH:{cultivo.ph_min}-{cultivo.ph_max} | L:{cultivo.luz_min}-{cultivo.luz_max}",
                    "actuador_temp": estados['temp'], "actuador_hum": estados['hum'],
                    "actuador_ph": estados['ph'], "actuador_luz": estados['luz']
                }
                
                await manager.send_personal_message(json.dumps(respuesta), websocket)
            except Exception as e:
                continue
    except WebSocketDisconnect:
        manager.disconnect(websocket)

if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=8000)