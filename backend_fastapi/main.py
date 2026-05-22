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
cluster = Cluster(['192.168.2.108'])
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

class CultivoEdicion(BaseModel):
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

# --- LÓGICA DE CONTROL (LAZO CERRADO) ---
def calcular_bits_estado(payload, cultivo):
    # Lógica: 100=ROJO(Exceso), 001=AZUL(Déficit), 010=VERDE(Óptimo)
    def obtener_bit(valor, min_val, max_val):
        if valor > max_val: return "100"
        if valor < min_val: return "001"
        return "010"

    t = obtener_bit(payload.get('temp', 0), cultivo.temp_min, cultivo.temp_max)
    h = obtener_bit(payload.get('hum', 0), cultivo.hum_min, cultivo.hum_max)
    p = obtener_bit(payload.get('ph', 0), cultivo.ph_min, cultivo.ph_max)
    l = obtener_bit(payload.get('luz', 0), cultivo.luz_min, cultivo.luz_max)
    return t + h + p + l

def verificar_token_ws(token: str):
    try:
        return jwt.decode(token, SECRET_KEY, algorithms=[ALGORITHM]).get("sub")
    except:
        return None

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
# --- WEBSOCKET (CONTROLADOR ACTIVO - REFORZADO) ---
@app.websocket("/ws/invernadero/{id_cultivo}")
async def websocket_endpoint(websocket: WebSocket, id_cultivo: str, token: str = None):
    # 1. ACEPTAR CONEXIÓN PRIMERO
    await manager.connect(websocket)
    
    # 2. VALIDAR
    if not token or not verificar_token_ws(token):
        print(f"DEBUG: Token inválido o ausente para {id_cultivo}")
        await websocket.close(code=status.WS_1008_POLICY_VIOLATION)
        return
    
    # 3. BUSCAR CULTIVO
    res = await asyncio.to_thread(session.execute, "SELECT * FROM config_cultivos WHERE id_cultivo = %s", (id_cultivo,))
    cultivo = res.one()
    
    if not cultivo:
        print(f"DEBUG: Cultivo {id_cultivo} no encontrado en DB")
        await websocket.close()
        return

    print(f"DEBUG: Conexión WebSocket establecida para {id_cultivo}")

    try:
        while True:
            data = await websocket.receive_text()
            payload = json.loads(data)
            comando_bits = calcular_bits_estado(payload, cultivo)
            await manager.send_personal_message(comando_bits, websocket)
    except WebSocketDisconnect:
        manager.disconnect(websocket)
        print("DEBUG: Cliente desconectado")

if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=8000)