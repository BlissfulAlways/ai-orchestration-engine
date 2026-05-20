package com.orchestrator.engine.model;

public class Goal{
    private String task;
    private String outputFormat;

    public String getTask(){
        return task;
    }

    public String getOutputFormat(){
        return outputFormat;
    }

    public void setTask(String task){
        this.task = task;
    }

    public void setOutputFormat(String outputFormat){
        this.outputFormat  = outputFormat;
    }

    @Override
    public String toString(){
        return "Goal : task = "+task+" outputFormat = "+outputFormat;
    }
}