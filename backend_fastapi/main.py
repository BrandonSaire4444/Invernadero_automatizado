import asyncio
import json
import serial
import os
from fastapi import FastAPI, WebSocket, WebSocketDisconnect, HTTPException, Depends, status, Request
from fastapi.security import OAuth2PasswordBearer
from pydantic import BaseModel
from cassandra.cluster import Cluster
from datetime import datetime, timedelta
import jwt  # Requiere: pip install PyJWT
import bcrypt  # 🔒 Usamos la librería nativa directamente para evitar fallas de compatibilidad

app = FastAPI(title="hidroponia_pro API", version="4.0")

# --- CONFIGURACIÓN DE SEGURIDAD (JWT Y CRYPTO) ---
SECRET_KEY = "SISTEMAS_AUDITORIA_BOLIVIA_SECURE_KEY"
ALGORITHM = "HS256"
ACCESS_TOKEN_EXPIRE_MINUTES = 60

oauth2_scheme = OAuth2PasswordBearer(tokenUrl="/api/login")

# --- FUNCIONES NATIVAS DE SEGURIDAD CON BCRYPT (REEMPLAZA A PASSLIB) ---
def generar_hash_password(password: str) -> str:
    """Codifica la contraseña en UTF-8 y genera un hash seguro usando sal nativa de Bcrypt"""
    password_bytes = password.encode('utf-8')
    sal = bcrypt.gensalt(rounds=12)
    hash_bytes = bcrypt.hashpw(password_bytes, sal)
    return hash_bytes.decode('utf-8')

def verificar_password(password_plana: str, hash_db: str) -> bool:
    """Compara matemáticamente la contraseña enviada con el hash limpio de Cassandra"""
    try:
        password_bytes = password_plana.encode('utf-8')
        hash_bytes = hash_db.encode('utf-8')
        return bcrypt.checkpw(password_bytes, hash_bytes)
    except Exception as e:
        print(f"💥 Error en verificación criptográfica: {e}")
        return False

# --- CONEXIÓN CASSANDRA (DOCKER LOCAL) ---
cluster = Cluster(['127.0.0.1'])
session = cluster.connect('hidroponia_pro')

# --- MODELOS DE SEGURIDAD Y REGISTRO ---
class Token(BaseModel):
    access_token: str
    token_type: str

class UsuarioRegistro(BaseModel):  
    nombre: str
    username: str
    password: str

class UsuarioLogin(BaseModel):  
    username: str
    password: str

# --- 🌿 MODELO ESTRUCTURADO PARA EXPANDIR EL DICCIONARIO DE DATOS ---
class CultivoRegistro(BaseModel):
    id_cultivo: str
    nombre_exótico: str  # Nombre comercial o común para la interfaz
    temp_min: float
    temp_max: float
    hum_min: float
    hum_max: float
    ph_min: float
    ph_max: float
    luz_min: float
    luz_max: float

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

# --- AUXILIARES DE SEGURIDAD (JWT) ---
def crear_token_acceso(data: dict):
    expiracion = datetime.utcnow() + timedelta(minutes=ACCESS_TOKEN_EXPIRE_MINUTES)
    to_encode = data.copy()
    to_encode.update({"exp": expiracion})
    return jwt.encode(to_encode, SECRET_KEY, algorithm=ALGORITHM)

def verificar_token_ws(token: str):
    try:
        payload = jwt.decode(token, SECRET_KEY, algorithms=[ALGORITHM])
        username: str = payload.get("sub")
        if username is None:
            return None
        return username
    except jwt.PyJWTError:
        return None

# --- LÓGICA DE REGULACIÓN DINÁMICA (HILO NO BLOQUEANTE) ---
def evaluar_regulacion_sync(id_cultivo: str, temp, hum, ph, luz):
    query = "SELECT temp_min, temp_max, hum_min, hum_max, ph_min, ph_max, luz_min, luz_max FROM config_cultivos WHERE id_cultivo = %s"
    config = session.execute(query, (id_cultivo,)).one()
    
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

# --- ENDPOINT 1: REGISTRO DE NUEVOS USUARIOS (PÚBLICO) ---
@app.post("/api/usuarios/registrar", status_code=status.HTTP_201_CREATED)
async def registrar_usuario(usuario: UsuarioRegistro):
    user_limpio = usuario.username.strip().lower()
    query_existe = "SELECT username FROM usuarios WHERE username = %s"
    resultado = await asyncio.to_thread(session.execute, query_existe, (user_limpio,))
    
    if resultado.one():
        raise HTTPException(status_code=400, detail="El nombre de usuario ya está registrado")
    
    password_segura = generar_hash_password(usuario.password.strip())
    
    query_insert = "INSERT INTO usuarios (username, nombre, password_hash) VALUES (%s, %s, %s)"
    await asyncio.to_thread(session.execute, query_insert, (user_limpio, usuario.nombre, password_segura))
    
    print(f"🌱 [CASSANDRA] Nuevo usuario registrado con éxito: '{user_limpio}'")
    return {"message": "Usuario registrado de manera exitosa"}

# --- 🔑 ENDPOINT 2: AUTENTICACIÓN (LOGIN CON AUTO-REPARACIÓN DE NOMBRE) ---
@app.post("/api/login")
async def login(request: Request):  
    print(f"\n--- 🕵️ AUDITORÍA DE ENTRADA SCADA ---")
    
    user_enviado = None
    pass_enviada = None
    
    try:
        payload = await request.json()
        user_enviado = payload.get("username") or payload.get("usuario") or payload.get("Username")
        pass_enviada = payload.get("password") or payload.get("pass") or payload.get("Password")
    except Exception:
        pass

    if not user_enviado:
        try:
            form_data = await request.form()
            if form_data:
                user_enviado = form_data.get("username") or form_data.get("usuario") or form_data.get("Username")
                pass_enviada = form_data.get("password") or form_data.get("pass") or form_data.get("Password")
        except Exception:
            pass

    if not user_enviado:
        params = dict(request.query_params)
        if params:
            user_enviado = params.get("username") or params.get("usuario") or params.get("Username")
            pass_enviada = params.get("password") or params.get("pass")

    if not user_enviado or not pass_enviada:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Estructura de credenciales no legible")

    user_limpio = str(user_enviado).strip().lower()
    pass_limpia = str(pass_enviada).strip()
    
    query = "SELECT username, nombre, password_hash FROM usuarios WHERE username = %s"
    resultado = session.execute(query, (user_limpio,)).one()
    
    if not resultado:
        print(f"❌ ERROR: El usuario '{user_limpio}' NO existe.")
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Usuario o contraseña incorrectos")
    
    hash_en_db = str(resultado.password_hash).strip().strip("'\"")
    
    if verificar_password(pass_limpia, hash_en_db):
        print("🔓 [ÉXITO] Contraseña válida.")
        
        if user_limpio != "paul" and resultado.nombre == "Paul Quispe":
            nuevo_nombre = user_limpio.capitalize() 
            print(f"✍️ [CORRECCIÓN] Detectado nombre replicado. Actualizando '{user_limpio}' a nombre real: '{nuevo_nombre}'...")
            
            query_update_nombre = "UPDATE usuarios SET nombre = %s WHERE username = %s"
            session.execute(query_update_nombre, (nuevo_nombre, user_limpio))
        
        token_acceso = crear_token_acceso(data={"sub": user_limpio})
        return {"JWT_TOKEN": token_acceso, "token_type": "bearer"}
    
    print("❌ ERROR: La contraseña no coincide.")
    raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Usuario o contraseña incorrectos")

# --- 🌿 ENDPOINT 3: CATALOGAR CULTIVOS OPERATIVOS (PARA EL SPINNER) ---
@app.get("/api/cultivos")
async def listar_cultivos():
    """Consulta Cassandra para extraer los identificadores que poblarán dinámicamente el Spinner de Android"""
    try:
        query = "SELECT id_cultivo FROM config_cultivos"
        rows = await asyncio.to_thread(session.execute, query)
        lista_cultivos = [row.id_cultivo for row in rows]
        
        if not lista_cultivos:
            lista_cultivos = ["lechuga_01", "tomate_hidro", "fresa_premium"]
            
        return {"cultivos": lista_cultivos}
    except Exception as e:
        print(f"⚠️ [AUDITORÍA] Error al mapear config_cultivos: {e}")
        return {"cultivos": ["lechuga_01"]}

# --- 🌿 ENDPOINT 4: REGISTRO DE NUEVA PLANTA (VALIDACIÓN DE MÍNIMOS Y MÁXIMOS) ---
@app.post("/api/cultivos/registrar", status_code=status.HTTP_201_CREATED)
async def registrar_cultivo(cultivo: CultivoRegistro):
    """Recibe la configuración del formulario flotante, procesa las reglas relacionales e indexa en Cassandra"""
    id_limpio = cultivo.id_cultivo.strip().lower().replace(" ", "_")
    
    if not id_limpio:
        raise HTTPException(status_code=400, detail="El ID del cultivo no puede ser un campo vacío")

    # 📏 REGLAS DE NEGOCIO REQUERIDAS: Condición estricta de orden matemático (Min < Max)
    if cultivo.temp_min >= cultivo.temp_max:
        raise HTTPException(status_code=400, detail="Error de Regla: Temperatura Mínima debe ser estrictamente menor a la Máxima")
    if cultivo.hum_min >= cultivo.hum_max:
        raise HTTPException(status_code=400, detail="Error de Regla: Humedad Mínima debe ser estrictamente menor a la Máxima")
    if cultivo.ph_min >= cultivo.ph_max:
        raise HTTPException(status_code=400, detail="Error de Regla: Nivel de pH Mínimo debe ser estrictamente menor al Máximo")
    if cultivo.luz_min >= cultivo.luz_max:
        raise HTTPException(status_code=400, detail="Error de Regla: Intensidad de Luz Mínima debe ser estrictamente menor a la Máxima")

    # 🛡️ VALIDACIÓN DE CONTROL DE ESCALA BIOLÓGICA (Sensores)
    if cultivo.ph_min < 0.0 or cultivo.ph_max > 14.0:
        raise HTTPException(status_code=400, detail="Error de Escala: El pH debe estar contenido en el rango físico de 0.0 a 14.0")

    # Control de redundancia: Evitar duplicados en la clave primaria de Cassandra
    query_existe = "SELECT id_cultivo FROM config_cultivos WHERE id_cultivo = %s"
    resultado = await asyncio.to_thread(session.execute, query_existe, (id_limpio,))
    if resultado.one():
        raise HTTPException(status_code=400, detail=f"El ID '{id_limpio}' ya se encuentra indexado en el Diccionario de Datos")

    # Inserción parametrizada y segura
    query_insert = """
        INSERT INTO config_cultivos (id_cultivo, temp_min, temp_max, hum_min, hum_max, ph_min, ph_max, luz_min, luz_max)
        VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s)
    """
    params = (
        id_limpio, cultivo.temp_min, cultivo.temp_max,
        cultivo.hum_min, cultivo.hum_max, cultivo.ph_min,
        cultivo.ph_max, cultivo.luz_min, cultivo.luz_max
    )
    await asyncio.to_thread(session.execute, query_insert, params)
    
    print(f"🌿 [CASSANDRA] Matriz de negocio expandida. Nuevo cultivo indexado: '{id_limpio}'")
    return {"message": "Cultivo registrado e indexado exitosamente en el Diccionario de Datos"}

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
                    
                    comando_bits = await asyncio.to_thread(evaluar_regulacion_sync, 'lechuga_01', temp, hum, ph, luz)
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

# --- FUNCIÓN AUXILIAR SÍNCRONA ---
def obtener_datos_bienvenida_sync(id_cultivo: str):
    try:
        query = "SELECT id_cultivo, temp_min, temp_max FROM config_cultivos WHERE id_cultivo = %s"
        config = session.execute(query, (id_cultivo,)).one()
        if config:
            return {
                "cultivo": f"🌱 Monitoreo Activo: {config.id_cultivo.upper()}",
                "ranges": f"Rangos Óptimos Temp: {config.temp_min}°C - {config.temp_max}°C (Cassandra)"
            }
    except Exception:
        pass
    return {
        "cultivo": f"🌱 Monitoreo Activo: {id_cultivo.upper()} (Por Defecto)",
        "rangos": "Rangos Estándar Sincronizados con el Diccionario de Datos"
    }

# --- ENDPOINT WEBSOCKET BLINDADO Y MULTI-PLANTA ---
@app.websocket("/ws/invernadero/{id_cultivo}")
async def websocket_endpoint(websocket: WebSocket, id_cultivo: str, token: str = None):
    if token is None or verificar_token_ws(token) is None:
        await websocket.close(code=status.WS_1008_POLICY_VIOLATION)
        print("🛑 [WEBSOCKET] Intento de conexión rechazado: Token JWT inválido o ausente.")
        return

    await manager.connect(websocket)
    print(f"\n🟢 [WEBSOCKET] Canal abierto para cultivo: [{id_cultivo}]. Esperando telemetría...")
    
    try:
        info_cultivo = await asyncio.to_thread(obtener_datos_bienvenida_sync, id_cultivo)
        init_payload = {
            "txt_verdura": info_cultivo["cultivo"],
            "txt_rangos": info_cultivo["rangos"] if "rangos" in info_cultivo else info_cultivo["ranges"],
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
                
                print(f"📥 [{id_cultivo.upper()}] Android -> Temp: {temp}°C | Hum: {hum}% | pH: {ph} | Luz: {luz}%")
                
                ahora = datetime.now()
                query = "INSERT INTO lecturas_sensores (origen, fecha_hora, temperatura, humedad, ph, luz) VALUES (%s, %s, %s, %s, %s, %s)"
                await asyncio.to_thread(session.execute, query, ('SIMULADOR_ANDROID', ahora, temp, hum, ph, luz))
                
                comando_bits = await asyncio.to_thread(evaluar_regulacion_sync, id_cultivo, temp, hum, ph, luz)
                respuesta_json = cmd_to_json(comando_bits)
                
                await manager.send_personal_message(json.dumps(respuesta_json), websocket)
                
            except (WebSocketDisconnect, ConnectionResetError):
                print(f"🔌 [WEBSOCKET] Conexión cerrada abruptamente para [{id_cultivo}].")
                break
            except KeyError as e:
                print(f"⚠️ [MALEABILIDAD] Estructura JSON inválida: {e}")
            except Exception as e:
                print(f"❌ [ERROR CONTROLADO] Detalle: {str(e)}")
                if "NoneType" in str(e) or "WebSocket" in str(e):
                    break
    finally:
        manager.disconnect(websocket)
        print(f"🧹 [INFRAESTRUCTURA] Memoria del socket para [{id_cultivo}] liberada exitosamente.")

@app.get("/")
def read_root():
    return {"status": "Servidor HidroponiaPro Operacional Dinámico", "motor": "FastAPI"}