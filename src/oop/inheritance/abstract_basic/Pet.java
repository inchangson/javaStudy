package oop.inheritance.abstract_basic;

abstract public class Pet {
    int age;
    int hunger;
    String name;

    public Pet() {
        age = 0;
        hunger = 10;
    }

    void eat() {
        hunger = Math.max(0, hunger - 1);
    }

    // abstract을 쓰려면 추상 클래스여야 한다
    abstract void bark();
}