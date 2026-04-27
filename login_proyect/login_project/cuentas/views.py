from django.shortcuts import render, redirect
from django.contrib.auth.forms import UserCreationForm
from django.contrib.auth.decorators import login_required
from django.contrib import messages
from django.contrib.auth import authenticate

# 🔥 IMPORTANTE para API
from rest_framework.decorators import api_view
from rest_framework.response import Response
from rest_framework.authtoken.models import Token


# ------------------------
# VISTAS WEB (LAS TUYAS)
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


@login_required
def dashboard(request):
    return render(request, 'cuentas/dashboard.html')


# ------------------------
# 🔥 API PARA FLUTTER
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