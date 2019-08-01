package com.example.demo.controller.testik;

import org.springframework.web.socket.WebSocketSession;
import processing.annotation.WebSocketController;
import processing.annotation.WebSocketHandler;

import java.util.List;

@WebSocketController(url = "/user", idKey = "id")
public class UserWsHandler {

    @WebSocketHandler(idValue = "login")
    public void handleLogin(WebSocketSession ws, DToshka dto) {
        System.out.println("lol login");
    }


    @WebSocketHandler(idValue = "zacini")
    public void handleZacini(WebSocketSession ws, DToshka dToshka) {
        System.out.println(dToshka);
        System.out.println(ws.getId());
        System.out.println("lol login");
    }
}
