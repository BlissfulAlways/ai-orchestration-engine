package com.orchestrator.engine.controller;

import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import com.orchestrator.engine.model.Goal;

@RestController
@RequestMapping("/orchestrator")
public class GoalController{
    @PostMapping("/goals")
    public String goalParser(@RequestBody Goal goal){
        System.out.println("Received goal : "+goal.toString());
        return "Goal received : "+goal.toString();
        //printed and sent the goal by calling the overriden method
    }
}