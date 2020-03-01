# Replicated File System based on Lamport’s Mutual Exclusion Algorithm
## Description
Implement a solution that mimics a replicated file system. The file system consists of four text files:
f1, f2, f3, and f4. All four files are replicated at three file servers. To achieve file replication, choose any
three of the dcxx machines. Each of these machines will store a copy of all the files. Five clients, executing
at different sites (and different from the three sites that replicate the files), may issue append to file requests
for any of these files. The clients know the locations of all the other file servers where the files are replicated. All replicas of a file are consistent, to begin with. The desired
operations are as follows:

- A client initiates at most one append to file at a time.

- The client may send a append to file REQUEST to any of the file replication sites (randomly selected)
along with the text to be appended. In this case, the site must report a successful message to the client
if all copies of the file, across the all sites, are updated consistently. Otherwise, the append should not
happen at any site and the site must report a failure message to the client. We do not expect a failure
to happen in this project unless a file is not replicated at exactly four different sites.

- On receiving the append to file REQUEST, the receiving site initiates a REQUEST to enter critical
section, as per Lamport’s mutual exclusion algorithm. Obviously each REQUEST should result in a
critical section execution regarding that particular file. In the critical section the text provided by the
client must be appended to all copies of the file.

- Concurrent appends initiated by different clients to different files must be supported as such updates
do not violate the mutual exclusion condition.

## Run

- put server.jar and config.txt on three different servers, execute 
`java -jar server.jar [id]`
id is from 0 to 2, represent "server1" to "server3" in the config.txt. Make sure using the right id. For example, if it is running on dc02.utdallas.edu, which is "server2" in the config.txt, the command should be `java -jar server.jar 1`
- put client.jar and config.txt on several different clients 
`java -jar client.jar [id]`
id start from 0, you can start as many clients as you like.

## Main Process

- Every server has socket listeners for all the clients and all the other servers
- client sends APPEND message to server and block until server responses
- When server receives an APPEND message, and there is no other pending request on the same file, the server will broadcast request message to other servers. Or it will push the APPEND message and process it when the other messages with smaller timestamp have been removed
- When a server receives a REQUEST message, it will REPLY to the sender, and places the message on request queue for the given file
- When a server receives all the REPLY messages from other servers, and it is the owner of the top message on request queue, it can enter critical section. After append local file, the server will broadcast RELEASE message to other servers, and RESPONSE to the client
- When a server receives a RELEASE message, it remove the request on top of the request queue, append local file, and process the next request if it is the owner of that request

## Synchronization

- Each file has a request queue, different files act as different critical sections
- Servers create muliple threads as socket listeners, they can receive and parse message concurrently. But processing message will enter critical section, only one thread can enter each time. This is because processing message will read and write timestamp of the server, which is mutual exclusive

## Serialization & Deserialization
[Gson](https://github.com/google/gson)