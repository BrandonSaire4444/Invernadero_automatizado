from django.urls import path
from django.contrib.auth.views import LoginView, LogoutView
from .views import inicio, registro, dashboard, api_login

urlpatterns = [
    path('', inicio, name='inicio'),
    path('login/', LoginView.as_view(template_name='cuentas/login.html'), name='login'),
    path('registro/', registro, name='registro'),
    path('dashboard/', dashboard, name='dashboard'),
    path('logout/', LogoutView.as_view(), name='logout'),

    # 🔥 ESTE ES EL IMPORTANTE
    path('api/login/', api_login),
]