from django.urls import path
from django.contrib.auth.views import LogoutView
from .views import inicio, registro, dashboard, api_login, login_flutter
 
urlpatterns = [
    path('', inicio, name='inicio'),
 
    # ← Ahora usa login_flutter en vez del LoginView de Django
    path('login/', login_flutter, name='login'),
 
    path('registro/', registro, name='registro'),
    path('dashboard/', dashboard, name='dashboard'),
    path('logout/', LogoutView.as_view(), name='logout'),
 
    # API para Flutter
    path('api/login/', api_login),
]