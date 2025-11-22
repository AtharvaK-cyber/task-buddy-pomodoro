                 +------------------------+
                 |        User            |
                 +-----------+------------+
                             |
        -------------------------------------------------
        |                 |                 |          |
   Add/Edit/Delete    Start Pomodoro     Export CSV   View Stats
        Tasks             Timer
Actors:
User
Use Cases:
Add task
Edit task
Delete task
Start Pomodoro
Stop Pomodoro
Export CSV
View analytics
Java Backend
+-------------------+
|      Task         |
+-------------------+
| id : String       |
| title : String    |
| dueDate : Date    |
| priority : String |
| completed : Bool  |
+-------------------+
| calculatePriority()|
| toJSON()          |
+-------------------+

        ▲
        |
        | contains multiple
        |
+-----------------------+
|     TaskManager       |
+-----------------------+
| tasks : List<Task>    |
+-----------------------+
| addTask()             |
| editTask()            |
| deleteTask()          |
| saveTasks()           |
| loadTasks()           |
+-----------------------+

+--------------------+
|  PomodoroSession   |
+--------------------+
| taskId : String    |
| duration : int     |
| startTime : Date   |
+--------------------+
| start()            |
| stop()             |
| logSession()       |
+--------------------+

+-------------------------+
|       TaskApp           |
+-------------------------+
| serverPort : int        |
+-------------------------+
| startServer()           |
| handleRequest()         |
| serveFrontend()         |
+-------------------------+
System Architecture
+------------------+       HTTP Requests      +------------------+
|   Frontend       | <----------------------> |     Backend      |
| HTML / CSS / JS  |                          | Java HTTP Server |
+------------------+                          +------------------+
        |                                              |
        | Reads/Writes                                 | Saves to
        v                                              v
+------------------+                          +------------------+
| Browser Storage  |                          |  JSON Files      |
| (UI state)       |                          | tasks.json       |
+------------------+                          | sessions.json    |
                                              +------------------+
     +---------+
     |  User   |
     +----+----+
          |
          v
 +--------------------+
 |  Task Buddy UI     |
 | (HTML/CSS/JS)      |
 +---------+----------+
           |
           v
 +---------------------+
 |   Java Backend      |
 |   HTTP Server       |
 +---------+-----------+
           |
           v
 +---------------------+
 |  JSON Storage       |
 | tasks.json          |
 | sessions.json       |
 +---------------------+


