from django.shortcuts import render, redirect
from django.contrib.auth.forms import UserCreationForm, AuthenticationForm
from django.contrib.auth.decorators import login_required
from django.contrib import messages
from django.contrib.auth import authenticate, login as auth_login
from django.http import HttpResponseRedirect
 
# IMPORTANTE para API
from rest_framework.decorators import api_view
from rest_framework.response import Response
from rest_framework.authtoken.models import Token
 
 
# ------------------------
# VISTAS WEB
# ------------------------
 
def inicio(request):
    return render(request, 'cuentas/inicio.html')
 
 
def registro(request):
    if request.method == 'POST':
        form = UserCreationForm(request.POST)
        if form.is_valid():
            form.save()
            messages.success(request, 'Usuario creado correctamente. Ahora inicia sesión.')
            return redirect('login')
    else:
        form = UserCreationForm()
    return render(request, 'cuentas/registro.html', {'form': form})
 
 
# ← Reemplaza el LoginView de Django
# Autentica y redirige directo al dashboard de Flutter
def login_flutter(request):
    # ← Pasa el formulario de Django para que el HTML lo renderice correctamente
    form = AuthenticationForm()
    error = None
 
    if request.method == 'POST':
        form = AuthenticationForm(request, data=request.POST)
        if form.is_valid():
            user = form.get_user()
            auth_login(request, user)
 
            # Obtener o crear el token del usuario
            token, _ = Token.objects.get_or_create(user=user)
 
            # Redirige al dashboard de Flutter con el token en la URL
            flutter_url = f"http://localhost:8080/?token={token.key}"
            return HttpResponseRedirect(flutter_url)
        else:
            error = 'Credenciales incorrectas'
 
    return render(request, 'cuentas/login.html', {'form': form, 'error': error})
 
 
@login_required
def dashboard(request):
    return render(request, 'cuentas/dashboard.html')
 
 
# ------------------------
# API PARA FLUTTER
# ------------------------
 
@api_view(['POST'])
def api_login(request):
    username = request.data.get('username')
    password = request.data.get('password')
 
    user = authenticate(username=username, password=password)
 
    if user is not None:
        token, created = Token.objects.get_or_create(user=user)
        return Response({
            'status': 'success',
            'token': token.key
        })
    else:
        return Response({
            'status': 'error',
            'message': 'Credenciales incorrectas'
        })