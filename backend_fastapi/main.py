import asyncio
import json
from fastapi import FastAPI, WebSocket, WebSocketDisconnect, HTTPException, status
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

# --- LÓGICA CORE (UNIFICADA) ---
async def guardar_datos_async(payload, estados, origen="SIMULADOR_ANDROID"):
    try:
        query_insert = """INSERT INTO lecturas_sensores (origen, fecha_hora, temperatura, humedad, ph, luz, act_temp, act_hum, act_ph, act_luz)
        VALUES (%s, toTimestamp(now()), %s, %s, %s, %s, %s, %s, %s, %s)"""
        await asyncio.to_thread(session.execute, query_insert, 
            (origen, float(payload['temp']), float(payload['hum']), float(payload['ph']), float(payload['luz']), 
             estados['temp'], estados['hum'], estados['ph'], estados['luz']))
    except Exception as e:
        print(f"DEBUG: Aviso de persistencia: {e}")

def calcular_estados_y_formatear(payload, cultivo):
    def obtener_estado(val, min_v, max_v):
        mid = (min_v + max_v) / 2
        tol = max((max_v - min_v) * 0.20, 0.1)
        if abs(val - mid) <= tol: return "VERDE"
        return "AZUL" if val < mid else "ROJO"

    estados = {
        "temp": obtener_estado(float(payload['temp']), cultivo.temp_min, cultivo.temp_max),
        "hum": obtener_estado(float(payload['hum']), cultivo.hum_min, cultivo.hum_max),
        "ph": obtener_estado(float(payload['ph']), cultivo.ph_min, cultivo.ph_max),
        "luz": obtener_estado(float(payload['luz']), cultivo.luz_min, cultivo.luz_max)
    }
    return estados

# --- ENDPOINTS API ---
@app.post("/api/esp32/telemetria/{nodo_id}")
async def telemetria_esp32(nodo_id: str, payload: dict):
    # 1. Consultar nodo actual
    res_nodo = await asyncio.to_thread(session.execute, 
        "SELECT id_cultivo FROM nodos_config WHERE nodo_id = %s", (nodo_id,))
    config = res_nodo.one()
    
    # Lógica de cambio de cultivo si el ESP32 envía 'cambiar': true
    if payload.get("cambiar") == True:
        # Aquí buscarías el siguiente ID en la lista (simplificado)
        cultivos = await asyncio.to_thread(session.execute, "SELECT id_cultivo FROM config_cultivos WHERE activo = true ALLOW FILTERING")
        lista = [c.id_cultivo for c in cultivos]
        idx = (lista.index(config.id_cultivo) + 1) % len(lista)
        nuevo_id = lista[idx]
        await asyncio.to_thread(session.execute, "UPDATE nodos_config SET id_cultivo = %s WHERE nodo_id = %s", (nuevo_id, nodo_id))
        config = type('obj', (object,), {'id_cultivo': nuevo_id}) # Actualizamos referencia local
    
    # 2. Consultar configuración del cultivo (ahora usa el cultivo actual o el nuevo)
    res_cultivo = await asyncio.to_thread(session.execute, 
        "SELECT * FROM config_cultivos WHERE id_cultivo = %s", (config.id_cultivo,))
    cultivo = res_cultivo.one()
    
    # 3. Calcular estados y añadir nombre_cultivo a la respuesta
    estados = calcular_estados_y_formatear(payload, cultivo)
    asyncio.create_task(guardar_datos_async(payload, estados, origen=nodo_id))
    
    respuesta = estados
    respuesta["nombre_cultivo"] = cultivo.nombre_verdura # <--- AGREGAR ESTO
    return respuesta

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

# --- WEBSOCKET ---
@app.websocket("/ws/invernadero/{id_cultivo}")
async def websocket_endpoint(websocket: WebSocket, id_cultivo: str, token: str = None):
    await manager.connect(websocket)
    if not token or (jwt.decode(token, SECRET_KEY, algorithms=[ALGORITHM]).get("sub") is None):
        await websocket.close(code=status.WS_1008_POLICY_VIOLATION); return
    
    res = await asyncio.to_thread(session.execute, "SELECT * FROM config_cultivos WHERE id_cultivo = %s", (id_cultivo,))
    cultivo = res.one()
    if not cultivo: await websocket.close(); return

    try:
        while True:
            data = await websocket.receive_text()
            payload = json.loads(data)
            estados = calcular_estados_y_formatear(payload, cultivo)
            asyncio.create_task(guardar_datos_async(payload, estados))
            
            respuesta = {
                "txt_verdura": cultivo.nombre_verdura,
                "actuador_temp": estados['temp'], "actuador_hum": estados['hum'],
                "actuador_ph": estados['ph'], "actuador_luz": estados['luz']
            }
            await manager.send_personal_message(json.dumps(respuesta), websocket)
    except WebSocketDisconnect:
        manager.disconnect(websocket)

if __name__ == "__main__":
    uvicorn.run(app, host="192.168.2.108", port=8000)