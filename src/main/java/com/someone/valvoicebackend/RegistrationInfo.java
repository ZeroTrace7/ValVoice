package com.someone.valvoicebackend;

public record RegistrationInfo(boolean registered, String signature, String salt) {
}